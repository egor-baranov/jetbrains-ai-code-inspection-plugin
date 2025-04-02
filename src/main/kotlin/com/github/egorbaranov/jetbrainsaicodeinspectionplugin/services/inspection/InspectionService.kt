package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.messages.Topic
import org.jdom.Element
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

@State(
    name = "InspectionService",
    storages = [Storage("InspectionServiceState.xml")]
)
@Service(Service.Level.PROJECT)
class InspectionService(private val project: Project) : PersistentStateComponent<Element> {
    val inspectionsById = ConcurrentHashMap<String, Inspection>()
    val inspectionFiles = ConcurrentHashMap<Inspection, MutableList<CodeFile>>()

    // Inspection management
    fun putInspection(inspection: Inspection, files: List<CodeFile>) {
        inspectionsById[inspection.id] = inspection
        inspectionFiles.getOrPut(inspection) { mutableListOf() }
        addFilesToInspection(inspection, files)
    }

    fun getInspectionById(id: String): Inspection? = inspectionsById[id]

    fun getInspections(): List<Inspection> = inspectionsById.values.toList()

    fun setInspectionDescription(id: String, description: String) {
        println("set inspection description: id=$id, description=$description")

        val inspection = inspectionsById[id] ?: return
        val newInspection = inspection.copy(description = description)
        val filesById = inspectionFiles[inspection].orEmpty().toMutableList()

        inspectionsById[id] = newInspection
        inspectionFiles[newInspection] = filesById
        inspectionFiles.remove(inspection)
    }

    fun addFilesToInspection(
        inspection: Inspection,
        files: List<CodeFile>,
        onPerformed: ((List<CodeFile>) -> Unit)? = null
    ) {
        val existingFiles = inspectionFiles[inspection]?.map { it.path }?.toSet().orEmpty()
        val filteredFiles = files.filter { !existingFiles.contains(it.path) }
        if (filteredFiles.size < 2) return

        inspectionLoading(inspection)

        performFixWithProgress(inspection, filteredFiles) {
            println("added files to inspection: ${filteredFiles.size}}")
            synchronized(inspectionFiles) {
                if (inspectionFiles[inspection] == null) {
                    inspectionFiles[inspection] = mutableListOf()
                }
                inspectionFiles[inspection]?.addAll(it)
            }
        }
    }

    fun performFixWithProgress(
        inspection: Inspection,
        codeFiles: List<CodeFile>,
        onPerformed: ((List<CodeFile>) -> Unit)? = null
    ) {
        val fixedFiles = mutableListOf<CodeFile>()
        val task = object : Task.Backgroundable(
            project,
            "Applying fixes for '${inspection.description}'",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    indicator.text = "Processing ${codeFiles.size} files..."

                    fixedFiles.addAll(OpenAIClient.getInstance(project).performFix(inspection, codeFiles))
                    indicator.checkCanceled()  // Throw exception if canceled

                    thisLogger().info("Added ${fixedFiles.size} fixed files to inspection ${inspection.id}")
                } catch (e: ProcessCanceledException) {
                    thisLogger().warn("Processing canceled for inspection ${inspection.id}")
                } catch (e: Exception) {
                    thisLogger().error("Failed to process files for inspection ${inspection.id}", e)
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            "Failed to apply fixes: ${e.message}",
                            "Inspection Error"
                        )
                    }
                } finally {
                    onPerformed?.let {
                        it(fixedFiles)
                    }
                    notifyInspectionsChanged()
                }
            }
        }

        ProgressManager.getInstance().run(task)
    }

    fun getFilesForInspection(inspection: Inspection): List<CodeFile> =
        inspectionFiles[inspection] ?: emptyList()

    fun inspectionLoading(inspection: Inspection) {
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).inspectionLoading(inspection)
    }

    fun removeInspection(inspection: Inspection) {
        inspectionsById.remove(inspection.id)
        inspectionFiles.remove(inspection)
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).removeInspection(inspection)
    }

    fun removeFileFromInspection(inspection: Inspection, codeFile: CodeFile) {
        inspectionFiles[inspection]?.remove(codeFile)
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).removeFileFromInspection(inspection, codeFile)
    }

    // Persistent state handling
    override fun getState(): Element {
        return Element("inspections").apply {
            inspectionsById.values.forEach { inspection ->
                addContent(Element("inspection").apply {
                    setAttribute("id", inspection.id)
                    setAttribute("description", inspection.description)
                    setAttribute("fixPrompt", inspection.fixPrompt)

                    inspectionFiles[inspection]?.let { files ->
                        addContent(Element("files").apply {
                            files.forEach { file ->
                                addContent(Element("file").apply {
                                    setAttribute("path", file.path)
                                    text = file.content
                                })
                            }
                        })
                    }
                })
            }
        }
    }

    override fun loadState(state: Element) {
        inspectionsById.clear()
        inspectionFiles.clear()

        state.children.forEach { inspectionElement ->
            val inspection = Inspection(
                id = inspectionElement.getAttributeValue("id") ?: "",
                description = inspectionElement.getAttributeValue("description") ?: "",
                fixPrompt = inspectionElement.getAttributeValue("fixPrompt") ?: ""
            )

            inspectionsById[inspection.id] = inspection

            inspectionElement.getChild("files")?.children?.forEach { fileElement ->
                val codeFile = CodeFile(
                    path = fileElement.getAttributeValue("path") ?: "",
                    content = fileElement.text
                )
                inspectionFiles.getOrPut(inspection) { mutableListOf() }.add(codeFile)
            }
        }

        thisLogger().debug("Loaded ${inspectionsById.size} inspections with files")
    }

    fun clearState() {
        synchronized(inspectionFiles) {
            synchronized(inspectionsById) {
                inspectionsById.clear()
                inspectionFiles.clear()
            }
        }
    }

    // Subscription handling
    private fun notifyInspectionsChanged() {
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).inspectionsChanged()
    }

    // Data classes
    data class Inspection(
        val id: String,
        val description: String,
        val fixPrompt: String
    ) {
        // Required for XML serialization
        constructor() : this("", "", "")
    }

    data class CodeFile(
        val path: String,
        val content: String
    ) {
        fun virtualFile() = VirtualFileManager.getInstance().findFileByUrl(path)
            ?.takeIf { it.isValid }

        // Required for XML serialization
        constructor() : this("", "")
    }

    interface InspectionChangeListener {
        fun inspectionLoading(inspection: Inspection)

        fun inspectionsChanged()

        fun removeInspection(inspection: Inspection)

        fun removeFileFromInspection(inspection: Inspection, codeFile: CodeFile)
    }

    companion object {
        fun getInstance(project: Project): InspectionService {
            return project.service()
        }

        val logger = LoggerFactory.getLogger(InspectionService::class.java)

        val INSPECTION_CHANGE_TOPIC = Topic.create(
            "AI Code Inspections Changed",
            InspectionChangeListener::class.java
        )
    }
}
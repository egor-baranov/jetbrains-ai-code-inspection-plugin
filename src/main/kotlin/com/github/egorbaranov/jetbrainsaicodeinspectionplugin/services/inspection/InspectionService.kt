package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.components.*
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.messages.Topic
import org.jdom.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

@State(
    name = "InspectionService",
    storages = [Storage("InspectionServiceState.xml")]
)
@Service(Service.Level.PROJECT)
class InspectionService(private val project: Project) : PersistentStateComponent<Element> {

    private val inspectionsById = ConcurrentHashMap<String, Inspection>()
    val inspectionFiles = ConcurrentHashMap<Inspection, MutableList<CodeFile>>()
    private val tasks = mutableListOf<Task>()

    fun putInspection(inspection: Inspection, files: List<CodeFile>) {
        logger.info("Putting inspection with id='${inspection.id}', description='${inspection.description}'")
        inspectionsById[inspection.id] = inspection
        inspectionFiles.getOrPut(inspection) { mutableListOf() }
        inspectionLoaded(inspection)
        addFilesToInspection(inspection, files)
    }

    fun getInspectionById(id: String): Inspection? {
        val inspection = inspectionsById[id]
        if (inspection == null) {
            logger.warn("Requested inspectionById='$id' but no such inspection exists")
        } else {
            logger.debug("Retrieved inspectionById='$id'")
        }
        return inspection
    }

    fun getInspections(): List<Inspection> = synchronized(inspectionFiles) {
        val list = inspectionFiles.keys.toList()
        logger.debug("Returning list of inspections: {}", list.map { it.id })
        list
    }

    fun getAffectedFiles(): List<CodeFile> = synchronized(inspectionFiles) {
        val list = inspectionFiles.values.toSet().flatten().toList()
        logger.debug("Returning affected files: {}", list.map { it.path })
        list
    }

    fun getTasks(): List<Task> {
        logger.debug("Current number of tasks: ${tasks.size}")
        return tasks
    }

    fun cancelAllTasks() {
        synchronized(tasks) {
            logger.info("Canceling all ${tasks.size} tasks")
            tasks.forEach { it.onCancel() }
            tasks.clear()
        }
    }

    fun setInspectionDescription(id: String, description: String) {
        val inspection = inspectionsById[id]
        if (inspection == null) {
            logger.warn("Cannot set description: no inspection found with id='$id'")
            return
        }
        val oldDescription = inspection.description
        val newInspection = inspection.copy(description = description)
        val filesById = inspectionFiles[inspection].orEmpty().toMutableList()

        inspectionsById[id] = newInspection
        inspectionFiles[newInspection] = filesById
        inspectionFiles.remove(inspection)

        logger.info("Updated inspection id='$id': description '$oldDescription' → '$description'")
    }

    fun addFilesToInspection(
        inspection: Inspection,
        files: List<CodeFile>
    ) {
        val pathToRequest: List<CodeFile> = synchronized(inspectionFiles) {
            val alreadyPresentPaths = inspectionFiles[inspection]?.map { it.path }?.toSet().orEmpty()
            val newPaths = files.filter { !alreadyPresentPaths.contains(it.path) }
            logger.debug(
                "For inspection id='{}', filtering files. Already present paths={}, newPaths={}",
                inspection.id,
                alreadyPresentPaths,
                newPaths.map { it.path })
            newPaths
        }

        if (pathToRequest.isEmpty()) {
            logger.debug("No new files to request for inspection id='${inspection.id}'")
            return
        }
        logger.info("Adding ${pathToRequest.size} new files to inspection id='${inspection.id}'")

        performFixWithProgress(inspection, pathToRequest) { fixedList ->
            val reallyNew: List<CodeFile> = synchronized(inspectionFiles) {
                val alreadyPaths2 = inspectionFiles[inspection]?.map { it.path }?.toSet().orEmpty()
                val notAlready = fixedList.filter { !alreadyPaths2.contains(it.path) }
                if (notAlready.isNotEmpty()) {
                    inspectionFiles.getOrPut(inspection) { mutableListOf() }.addAll(notAlready)
                    logger.info("Inspection id='${inspection.id}' - added fixed files: ${notAlready.map { it.path }}")
                }
                notAlready
            }

            if (reallyNew.isNotEmpty()) {
                addFiles(inspection, reallyNew)
            }
        }
    }

    fun performFixWithProgress(
        inspection: Inspection,
        codeFiles: List<CodeFile>,
        onPerformed: ((List<CodeFile>) -> Unit)? = null
    ): Task {
        val fixedFiles = mutableListOf<CodeFile>()
        val task = object : Task.Backgroundable(
            project,
            "Applying fixes for '${inspection.description}'",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                logger.info("Starting background task for inspection id='${inspection.id}' with ${codeFiles.size} files")
                try {
                    indicator.isIndeterminate = false
                    val total = codeFiles.size
                    for ((index, file) in codeFiles.withIndex()) {
                        indicator.checkCanceled()
                        indicator.fraction = index / total.toDouble()
                        indicator.text = "Processing file ${index + 1} of $total: ${file.path}"
                        logger.debug("Processing file '${file.path}' for inspection id='${inspection.id}', progress ${index + 1}/$total")

                        val singleFileResult = OpenAIClient.getInstance(project).performFix(inspection, listOf(file))
                        fixedFiles.addAll(singleFileResult)
                        logger.debug("Fix returned ${singleFileResult.size} file(s) for '${file.path}'")
                    }

                    indicator.checkCanceled()
                    logger.info("Finished processing ${fixedFiles.size} fixed files for inspection id='${inspection.id}'")
                }
                catch (pce: ProcessCanceledException) {
                    MetricService.getInstance(project).error(pce)
                    logger.warn("Processing cancelled for inspection id='${inspection.id}'")
                    throw pce
                }
                catch (e: Exception) {
                    MetricService.getInstance(project).error(e)
                    logger.error("Failed to process files for inspection id='${inspection.id}'", e)
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            "Failed to apply fixes: ${e.message}",
                            "Inspection Error"
                        )
                    }
                }
                finally {
                    onPerformed?.invoke(fixedFiles)
                }
            }

            override fun onFinished() {
                super.onFinished()
                tasks.remove(this)
                logger.info("Background task finished for inspection id='${inspection.id}'. Remaining tasks: ${tasks.size}")
            }

            override fun onCancel() {
                super.onCancel()
                tasks.remove(this)
                logger.info("Background task manually cancelled for inspection id='${inspection.id}'. Remaining tasks: ${tasks.size}")
            }
        }

        tasks.add(task)
        logger.info("Scheduling background task for inspection id='${inspection.id}'. Total scheduled tasks: ${tasks.size}")
        ProgressManager.getInstance().run(task)
        return task
    }

    fun inspectionLoading(inspectionId: UUID) {
        logger.info("Publishing event: inspectionLoading for id='$inspectionId'")
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).inspectionLoading(inspectionId)
    }

    private fun inspectionLoaded(inspection: Inspection) {
        logger.info("Publishing event: inspectionLoaded for id='${inspection.id}'")
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).inspectionLoaded(inspection)
    }

    private fun addFiles(inspection: Inspection, codeFiles: List<CodeFile>) {
        logger.info("Publishing event: addFilesToInspection for id='${inspection.id}', files=${codeFiles.map { it.path }}")
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).addFilesToInspection(inspection, codeFiles)
    }

    fun cancelInspection(inspectionId: UUID) {
        logger.info("Publishing event: inspectionCancelled for id='$inspectionId'")
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).inspectionCancelled(inspectionId)
    }

    fun removeInspection(inspection: Inspection) {
        inspectionsById.remove(inspection.id)
        inspectionFiles.remove(inspection)
        logger.info("Removed inspection id='${inspection.id}'")
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).removeInspection(inspection)
    }

    fun removeFileFromInspection(inspection: Inspection, codeFile: CodeFile) {
        inspectionFiles[inspection]?.remove(codeFile)
        logger.info("Removed file '${codeFile.path}' from inspection id='${inspection.id}'")
        project.messageBus.syncPublisher(INSPECTION_CHANGE_TOPIC).removeFileFromInspection(inspection, codeFile)
    }

    override fun getState(): Element {
        logger.debug("Serializing state for ${inspectionsById.size} inspections")
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
                id = inspectionElement.getAttributeValue("id").orEmpty(),
                description = inspectionElement.getAttributeValue("description").orEmpty(),
                fixPrompt = inspectionElement.getAttributeValue("fixPrompt").orEmpty()
            )
            inspectionsById[inspection.id] = inspection

            inspectionElement.getChild("files")?.children?.forEach { fileElement ->
                val codeFile = CodeFile(
                    path = fileElement.getAttributeValue("path") ?: "",
                    content = fileElement.text
                )
                inspectionFiles.getOrPut(inspection) { mutableListOf() }.add(codeFile)
            }
            logger.debug("Loaded inspection id='${inspection.id}' with ${inspectionFiles[inspection]?.size ?: 0} file(s)")
        }

        logger.info("Loaded ${inspectionsById.size} inspections with their files")
    }

    fun clearState() {
        synchronized(inspectionFiles) {
            synchronized(inspectionsById) {
                inspectionsById.clear()
                inspectionFiles.clear()
                logger.info("Cleared all inspections and files from state")
            }
        }
    }

    data class Inspection(
        val id: String,
        val description: String,
        val fixPrompt: String
    ) {
        constructor() : this("", "", "")
    }

    data class CodeFile(
        val path: String,
        val content: String
    ) {
        fun virtualFile() = VirtualFileManager.getInstance().findFileByUrl(path)
            ?.takeIf { it.isValid }
    }

    interface InspectionChangeListener {
        fun inspectionLoading(inspectionId: UUID)
        fun inspectionLoaded(inspection: Inspection)
        fun inspectionCancelled(inspectionId: UUID)
        fun addFilesToInspection(inspection: Inspection, codeFiles: List<CodeFile>)
        fun removeInspection(inspection: Inspection)
        fun removeFileFromInspection(inspection: Inspection, codeFile: CodeFile)
    }

    companion object {
        fun getInstance(project: Project): InspectionService = project.service()

        val logger: Logger = LoggerFactory.getLogger(InspectionService::class.java)
        val INSPECTION_CHANGE_TOPIC = Topic.create(
            "AI Code Inspections Changed",
            InspectionChangeListener::class.java
        )
    }
}

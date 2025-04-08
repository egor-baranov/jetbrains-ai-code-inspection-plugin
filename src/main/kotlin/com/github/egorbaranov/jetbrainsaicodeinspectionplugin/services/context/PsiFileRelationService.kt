package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.messages.Topic
import org.jdom.Element
import java.util.concurrent.ConcurrentHashMap

@State(
    name = "PsiFileRelationService",
    storages = [Storage("PsiFileRelations.xml")]
)
@Service(Service.Level.PROJECT)
class PsiFileRelationService : PersistentStateComponent<Element> {
    private val relations = ConcurrentHashMap<String, MutableSet<String>>()

    fun addRelation(source: PsiFile, related: PsiFile) {
        val sourceUrl = source.virtualFile.url
        val relatedUrl = related.virtualFile.url

        relations.compute(sourceUrl) { _, urls ->
            (urls ?: ConcurrentHashMap.newKeySet()).apply {
                add(relatedUrl)
            }
        }

        notifyRelationsChanged(source)
        logState("After adding relation")
    }

    @RequiresEdt
    fun getRelations(project: Project): Map<PsiFile, List<PsiFile>> {
//        ThreadingAssertions.assertEventDispatchThread()

        return ReadAction.compute<Map<PsiFile, List<PsiFile>>, Throwable> {
            relations.entries.mapNotNull { (sourceUrl, targetUrls) ->
                val sourceFile = getValidFile(project, sourceUrl) ?: return@mapNotNull null
                val targetFiles = targetUrls.mapNotNull { getValidFile(project, it) }

                sourceFile to targetFiles
            }.toMap()
        }
    }

    private fun getValidFile(project: Project, url: String): PsiFile? {
        return try {
            VirtualFileManager.getInstance().findFileByUrl(url)
                ?.takeIf { it.isValid }
                ?.let { PsiManager.getInstance(project).findFile(it) }
                ?.takeIf { it.isValid }
        } catch (e: Exception) {
            MetricService.getInstance(project).error(e)
            null
        }
    }

    fun getRelatedFiles(source: PsiFile): List<PsiFile> {
        val sourceUrl = source.virtualFile.url
        return relations[sourceUrl]
            ?.mapNotNull { url ->
                getFile(source.project, url)
            }
            ?.filter { it.isValid }
            ?.distinct()
            ?: emptyList()
    }

    fun removeRelation(source: PsiFile, related: PsiFile) {
        val sourceUrl = source.virtualFile.url
        val relatedUrl = related.virtualFile.url

        relations.computeIfPresent(sourceUrl) { _, urls ->
            urls.apply { remove(relatedUrl) }
        }

        notifyRelationsChanged(source)
        logState("After removing relation")
    }

    override fun getState(): Element {
        return Element("PsiFileRelations").apply {
            relations.forEach { (source, relatedUrls) ->
                addContent(Element("relation").apply {
                    setAttribute("source", source)
                    setAttribute("related", relatedUrls.joinToString("|"))
                })
            }
        }
    }

    override fun loadState(state: Element) {
        relations.clear()
        state.children.forEach { entry ->
            entry.getAttributeValue("source")?.let { source ->
                val relatedUrls = entry.getAttributeValue("related")
                    ?.splitToSequence("|")
                    ?.filterNot { it.isBlank() }
                    ?.toMutableSet()
                    ?: mutableSetOf()

                relations[source] = relatedUrls
            }
        }
        logState("After loading state")
    }

    fun cleanupRelations() {
        relations.keys.toSet().forEach { sourceUrl ->
            relations.computeIfPresent(sourceUrl) { _, urls ->
                urls.filterTo(ConcurrentHashMap.newKeySet()) { url ->
                    VirtualFileManager.getInstance().findFileByUrl(url)?.isValid == true
                }
            }
        }
        logState("After cleanup")
    }

    private fun getFile(project: Project, url: String): PsiFile? {
        return VirtualFileManager.getInstance().findFileByUrl(url)
            ?.takeIf { it.isValid }
            ?.let { PsiManager.getInstance(project).findFile(it) }
    }

    private fun notifyRelationsChanged(file: PsiFile) {
        file.project.messageBus.syncPublisher(RELATION_CHANGE_TOPIC).relationsChanged(file)
    }

    private fun logState(context: String) {
        thisLogger().debug(
            "$context:\n${
                relations.entries.joinToString("\n") { (source, targets) ->
                    "${source.substringAfterLast('/')} -> [${targets.joinToString { it.substringAfterLast('/') }}]"
                }
            }"
        )
    }

    companion object {
        fun getInstance(project: Project): PsiFileRelationService {
            return project.service()
        }

        val RELATION_CHANGE_TOPIC = Topic.create(
            "PsiFileRelationsChanges",
            RelationChangeListener::class.java
        )
    }

    interface RelationChangeListener {
        fun relationsChanged(changedFile: PsiFile)
    }
}
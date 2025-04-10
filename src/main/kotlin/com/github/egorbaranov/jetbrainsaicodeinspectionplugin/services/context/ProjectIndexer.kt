package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProjectIndexer(private val project: Project) {
    private val isIndexing = AtomicBoolean(false)
    private val indexData = ConcurrentHashMap<String, PsiElement>()
    private var currentIndicator: ProgressIndicator? = null

    interface IndexingHandler {
        fun shouldProcess(element: PsiElement): Boolean
        suspend fun processElement(element: PsiElement)
        fun onComplete(index: Map<String, PsiElement>)
        fun onError(error: Throwable)
    }

    fun startIndexing(handler: IndexingHandler) {
        if (!isIndexing.compareAndSet(false, true)) return

        DumbService.getInstance(project).runWhenSmart {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "Indexing project structure",
                true,
                ALWAYS_BACKGROUND
            ) {
                override fun run(indicator: ProgressIndicator) {
                    currentIndicator = indicator
                    try {
                        processProject(handler, indicator)
                        handler.onComplete(indexData.toMap())
                    } catch (e: ProcessCanceledException) {
                        MetricService.getInstance(project).error(e)
                        logger.info("Indexing cancelled")
                    } catch (e: Exception) {
                        MetricService.getInstance(project).error(e)
                        handler.onError(e)
                    } finally {
                        isIndexing.set(false)
                        currentIndicator = null
                    }
                }
            })
        }
    }

    private fun processProject(handler: IndexingHandler, indicator: ProgressIndicator) {
        val roots = readAction {
            ProjectRootManager.getInstance(project).contentSourceRoots
        }

        roots.forEach { root ->
            indicator.checkCanceled()
            processRoot(root, handler, indicator)
        }
    }

    private fun processRoot(root: VirtualFile, handler: IndexingHandler, indicator: ProgressIndicator) {
        val psiDir = readAction {
            PsiManager.getInstance(project).findDirectory(root)
        } ?: return

        processDirectory(psiDir, handler, indicator)
    }

    private fun processDirectory(
        dir: PsiDirectory,
        handler: IndexingHandler,
        indicator: ProgressIndicator
    ) {
        indicator.checkCanceled()
        indicator.text2 = "Indexing: ${dir.virtualFile.url}"

        processElements(dir, handler, indicator)

        readAction {
            dir.children.forEach { child ->
                when (child) {
                    is PsiFile -> processFile(child, handler, indicator)
                    is PsiDirectory -> processDirectory(child, handler, indicator)
                }
            }
        }
    }

    private fun processFile(
        file: PsiFile,
        handler: IndexingHandler,
        indicator: ProgressIndicator
    ) {
        indicator.checkCanceled()
        indicator.text = "Processing file: ${file.name}"
        indicator.fraction = calculateProgress(file)

        processElements(file, handler, indicator)
    }

    private fun processElements(
        container: PsiElement,
        handler: IndexingHandler,
        indicator: ProgressIndicator
    ) {
        readAction {
            PsiTreeUtil.processElements(container) { element ->
                indicator.checkCanceled()
                if (handler.shouldProcess(element)) {
                    indexData[element.hashCode().toString()] = element
                    launchProcessor(element, handler)
                }
                true
            }
        }
    }

    private fun launchProcessor(element: PsiElement, handler: IndexingHandler) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                handler.processElement(element)
            } catch (e: Exception) {
                MetricService.getInstance(project).error(e)
                logger.error("Error processing element", e)
            }
        }
    }

    fun stopIndexing() {
        currentIndicator?.cancel()
        isIndexing.set(false)
    }

    fun isIndexing() = isIndexing.get()

    private fun calculateProgress(file: PsiFile): Double {
        return (indexData.size % 100) / 100.0
    }

    private inline fun <T> readAction(noinline action: () -> T): T {
        return ApplicationManager.getApplication().runReadAction<T>(action)
    }

    companion object {
        private val logger = logger<ProjectIndexer>()
    }
}
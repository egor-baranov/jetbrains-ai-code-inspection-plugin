package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProjectIndexer(private val project: Project) {
    private val isIndexing = AtomicBoolean(false)
    private val indexData = ConcurrentHashMap<String, PsiElement>()
    private var currentIndicator: ProgressIndicator? = null
    private var indexingJob: Job? = null

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
                        runBlocking {
                            val supervisor = SupervisorJob()
                            indexingJob = supervisor
                            val exceptionHandler = CoroutineExceptionHandler { _, e ->
                                if (e !is CancellationException) {
                                    handler.onError(e)
                                }
                            }
                            val scope = CoroutineScope(Dispatchers.Default + supervisor + exceptionHandler)

                            processProject(handler, indicator, scope)

                            supervisor.children.forEach { it.join() }
                            supervisor.complete()
                        }
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
                        indexingJob = null
                    }
                }
            })
        }
    }

    private fun processProject(
        handler: IndexingHandler,
        indicator: ProgressIndicator,
        scope: CoroutineScope
    ) {
        val roots = runReadAction {
            ProjectRootManager.getInstance(project).contentSourceRoots
        }

        roots.forEach { root ->
            indicator.checkCanceled()
            processRoot(root, handler, indicator, scope)
        }
    }

    private fun processRoot(
        root: VirtualFile,
        handler: IndexingHandler,
        indicator: ProgressIndicator,
        scope: CoroutineScope
    ) {
        val psiDir = runReadAction {
            PsiManager.getInstance(project).findDirectory(root)?.takeIf { it.isValid }
        } ?: return

        processDirectory(psiDir, handler, indicator, scope)
    }

    private fun processDirectory(
        dir: PsiDirectory,
        handler: IndexingHandler,
        indicator: ProgressIndicator,
        scope: CoroutineScope
    ) {
        runReadAction {
            if (!dir.isValid) return@runReadAction
            indicator.checkCanceled()
            indicator.text2 = "Indexing: ${dir.virtualFile.url}"

            processElements(dir, handler, indicator, scope)

            dir.children.forEach { child ->
                when (child) {
                    is PsiFile -> processFile(child, handler, indicator, scope)
                    is PsiDirectory -> processDirectory(child, handler, indicator, scope)
                }
            }
        }
    }

    private fun processFile(
        file: PsiFile,
        handler: IndexingHandler,
        indicator: ProgressIndicator,
        scope: CoroutineScope
    ) {
        runReadAction {
            if (!file.isValid) return@runReadAction
            indicator.checkCanceled()
            indicator.text = "Processing file: ${file.name}"
            indicator.fraction = calculateProgress()

            processElements(file, handler, indicator, scope)
        }
    }

    private fun processElements(
        container: PsiElement,
        handler: IndexingHandler,
        indicator: ProgressIndicator,
        scope: CoroutineScope
    ) {
        runReadAction {
            if (!container.isValid) return@runReadAction
            PsiTreeUtil.processElements(container) { element ->
                try {
                    indicator.checkCanceled()
                    if (element.isValid && handler.shouldProcess(element)) {
                        val key = runReadAction { getPersistentKey(element) }
                        indexData[key] = element
                        scope.launch {
                            try {
                                // Check validity again in case element changed before coroutine started
                                val isValid = runReadAction { element.isValid }
                                if (isValid) {
                                    handler.processElement(element)
                                } else {
                                    indexData.remove(key)
                                }
                            } catch (e: PsiInvalidElementAccessException) {
                                logger.warn("Skipped invalid element: ${e.message}")
                                indexData.remove(key)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                MetricService.getInstance(project).error(e)
                                logger.warn("Error processing element", e)
                                indexData.remove(key)
                            }
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Error during element processing", e)
                }
                true
            }
        }
    }

    fun stopIndexing() {
        currentIndicator?.cancel()
        indexingJob?.cancel("Indexing cancelled by user")
        isIndexing.set(false)
    }

    fun isIndexing() = isIndexing.get()

    private fun calculateProgress(): Double {
        return if (indexData.isEmpty()) 0.0 else (indexData.size % 100) / 100.0
    }

    private fun getPersistentKey(element: PsiElement): String {
        return runReadAction {
            "${element.javaClass.name}-${element.containingFile?.name}-${element.textOffset}"
        }
    }

    companion object {
        private val logger = logger<ProjectIndexer>()
    }
}
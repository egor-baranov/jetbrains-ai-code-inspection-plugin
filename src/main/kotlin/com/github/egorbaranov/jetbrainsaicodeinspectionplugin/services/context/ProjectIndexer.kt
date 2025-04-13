package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class ProjectIndexer(private val project: Project) {

    private val isIndexing = AtomicBoolean(false)
    private var currentIndicator: ProgressIndicator? = null
    private var indexingJob: Job? = null
    private val contextProcessor = ContextProcessor(project)

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
                            contextProcessor.processProject(handler, indicator, scope)

                            supervisor.children.forEach { it.join() }
                            supervisor.complete()
                        }

                        handler.onComplete(contextProcessor.indexData.toMap())
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

    companion object {
        private val logger = logger<ProjectIndexer>()
    }
}
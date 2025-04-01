package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProjectIndexer(private val project: Project) {
    private val isIndexing = AtomicBoolean(false)
    private val indexData = ConcurrentHashMap<String, PsiElement>()
    private var currentIndicator: ProgressIndicator? = null

    interface IndexingHandler {
        fun shouldProcess(element: PsiElement): Boolean
        fun processElement(element: PsiElement)
        fun onComplete(index: Map<String, PsiElement>)
        fun onError(error: Throwable)
    }

    fun startIndexing(handler: IndexingHandler) {
        if (!isIndexing.compareAndSet(false, true)) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Indexing project structure",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                try {
                    indicator.isIndeterminate = false
                    val startTime = System.currentTimeMillis()

                    processProject(handler, indicator)

                    val duration = System.currentTimeMillis() - startTime
                    handler.onComplete(indexData.toMap())
                } catch (e: Exception) {
                    handler.onError(e)
                } finally {
                    isIndexing.set(false)
                    currentIndicator = null
                }
            }
        })
    }

    private fun processProject(handler: IndexingHandler, indicator: ProgressIndicator) {
        val virtualFiles = ProjectRootManager.getInstance(project)
            .contentSourceRoots

        virtualFiles.forEach { root ->
            if (indicator.isCanceled) return

            // Wrap PSI access in read action
            val dir = ApplicationManager.getApplication().runReadAction<PsiDirectory?> {
                PsiManager.getInstance(project).findDirectory(root)
            }

            dir?.let { psiDir ->
                ApplicationManager.getApplication().runReadAction {
                    processDirectory(psiDir, handler, indicator)
                }
            }
        }
    }

    private fun processDirectory(
        dir: PsiDirectory,
        handler: IndexingHandler,
        indicator: ProgressIndicator
    ) {
        indicator.checkCanceled()
        indicator.text2 = "Indexing: ${dir.virtualFile.url}"

        // Process elements in read action
        ApplicationManager.getApplication().runReadAction {
            PsiTreeUtil.processElements(dir) { element ->
                if (handler.shouldProcess(element)) {
                    indexData[element.hashCode().toString()] = element
                    handler.processElement(element)
                }
                !indicator.isCanceled
            }
        }

        // Process children in read action
        ApplicationManager.getApplication().runReadAction {
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
        indicator.fraction = calculateProgress()

        ApplicationManager.getApplication().runReadAction {
            PsiTreeUtil.processElements(file) { element ->
                if (handler.shouldProcess(element)) {
                    indexData[element.hashCode().toString()] = element
                    handler.processElement(element)
                }
                !indicator.isCanceled
            }
        }
    }

    fun stopIndexing() {
        currentIndicator?.cancel()
        isIndexing.set(false)
    }

    fun isIndexing() = isIndexing.get()

    private fun calculateProgress(): Double {
        // Implement your progress calculation logic
        return 0.0
    }
}
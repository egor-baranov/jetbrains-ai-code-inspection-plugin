package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

// TODO: make singleton
class ContextProcessor(
    private val project: Project
) {

    val indexData = ConcurrentHashMap<String, PsiElement>()

    fun processProject(
        handler: ProjectIndexer.IndexingHandler,
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
        handler: ProjectIndexer.IndexingHandler,
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
        handler: ProjectIndexer.IndexingHandler,
        indicator: ProgressIndicator,
        scope: CoroutineScope
    ) {
        if (!dir.isValid) return
        indicator.checkCanceled()
        indicator.text2 = "Indexing: ${dir.virtualFile.url}"

        dir.children.forEach { child ->
            when (child) {
                is PsiFile -> processFile(child, handler, indicator, scope)
                is PsiDirectory -> processDirectory(child, handler, indicator, scope)
            }
        }
    }

    private fun processFile(
        file: PsiFile,
        handler: ProjectIndexer.IndexingHandler,
        indicator: ProgressIndicator,
        scope: CoroutineScope
    ) {
        if (!file.isValid) return
        indicator.checkCanceled()
        indicator.text = "Processing file: ${file.name}"
        indicator.fraction = calculateProgress()

        processElements(file, handler, indicator, scope)
    }

    private fun processElements(
        container: PsiElement,
        handler: ProjectIndexer.IndexingHandler,
        indicator: ProgressIndicator,
        scope: CoroutineScope
    ) {
        if (!container.isValid) return
        PsiTreeUtil.processElements(container) { element ->
            try {
                indicator.checkCanceled()
                if (element.isValid && handler.shouldProcess(element)) {
                    val key = runReadAction { getPersistentKey(element) }
                    indexData[key] = element
                    scope.launch {
                        try {
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

    private fun calculateProgress(): Double {
        return if (indexData.isEmpty()) 0.0 else (indexData.size % 100) / 100.0
    }

    private fun getPersistentKey(element: PsiElement): String {
        return runReadAction {
            "${element.javaClass.name}-${element.containingFile?.name}-${element.textOffset}"
        }
    }

    companion object {
        private val logger = logger<ContextProcessor>()
    }
}
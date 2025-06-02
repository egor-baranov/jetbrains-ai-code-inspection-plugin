package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.task

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi.PsiCrawler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.application
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FileAnalyzerTask(
    private val project: Project,
    private val inspectionOffset: Int = 3,
    private val onProgressUpdate: (String) -> Unit = {},
    private val onComplete: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val onCancel: (Task) -> Unit = {}
) : Task.Backgroundable(project, "Analyzing file relations", true) {

    override fun run(indicator: ProgressIndicator) {
        try {
            val files = getOpenedFiles(project)
            if (files.isEmpty()) {
                onComplete()
                return
            }

            val processed = AtomicInteger(0)
            val toProcess = files.take(inspectionOffset)
            val total = toProcess.size

            for (file in toProcess) {
                indicator.checkCanceled()

                val relatedFuture: CompletableFuture<List<PsiFile>> =
                    PsiCrawler.getInstance(project).getFilesAsync(file)
                val relatedFiles: List<PsiFile> = try {
                    relatedFuture.get(5, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    throw if (e.cause is ProcessCanceledException) {
                        ProcessCanceledException()
                    } else {
                        e
                    }
                }

                indicator.checkCanceled()

                val toAnalyze = relatedFiles.filterNot { it == file }
                if (toAnalyze.isNotEmpty()) {
                    updateProgress(indicator, file.name, processed.get(), total)

                    try {
                        OpenAIClient
                            .getInstance(project)
                            .analyzeFile(file, toAnalyze, inspectionOffset)
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        MetricService.getInstance(project).error(e)
                        throw e
                    }
                } else {
                    updateProgress(indicator, file.name, processed.get(), total)
                }

                processed.incrementAndGet()
            }

            onComplete()
        } catch (pce: ProcessCanceledException) {
            MetricService.getInstance(project).error(pce)
            handleCancellation()
            throw pce
        } catch (e: Exception) {
            MetricService.getInstance(project).error(e)
            handleError(e)
        }
    }

    private fun getOpenedFiles(project: Project): List<PsiFile> {
        val openVFiles = FileEditorManager.getInstance(project).openFiles.toList()
        return ReadAction.compute<List<PsiFile>, Throwable> {
            val psiManager = PsiManager.getInstance(project)
            openVFiles.mapNotNull { vf -> psiManager.findFile(vf) }
        }
    }

    private fun updateProgress(
        indicator: ProgressIndicator,
        fileName: String,
        processed: Int,
        total: Int
    ) {
        indicator.apply {
            text = "Analyzing $fileName"
            fraction = processed.toDouble() / total.coerceAtLeast(1)
            text2 = "Processed $processed of $total files"
        }

        application.invokeLater {
            onProgressUpdate("Analyzing $fileName (${processed + 1}/$total)")
        }
    }

    private fun handleCancellation() {
        application.invokeLater {
            onCancel(this)
            onProgressUpdate("Analysis cancelled")
        }
    }

    private fun handleError(e: Throwable) {
        application.invokeLater {
            onError(e)
            Messages.showErrorDialog(
                "Analysis failed: ${e.message}",
                "Error"
            )
        }
    }
}

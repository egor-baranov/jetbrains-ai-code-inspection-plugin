package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.task

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.AnalysisResult
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
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FileAnalyzerTask(
    private val project: Project,
    private val inspectionOffset: Int = 3,
    private val onProgressUpdate: (String) -> Unit = {},
    private val onComplete: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Task.Backgroundable(project, "Analyzing file relations", true) {

    override fun run(indicator: ProgressIndicator) {
        try {
            val files = getOpenedFiles(project)
            if (files.isEmpty()) {
                onComplete()
                return
            }

            val processed = AtomicInteger(0)
            val total = files.size

            val allFutures = files.map { file ->
                PsiCrawler.getInstance(project)
                    .getFilesAsync(file)
                    .thenAcceptAsync({ relatedFiles ->
                        if (indicator.isCanceled) throw ProcessCanceledException()

                        val toProcess = relatedFiles.filterNot { it == file }
                        if (toProcess.isEmpty()) {
                            processed.incrementAndGet()
                        } else {
                            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                                try {
                                    updateProgress(indicator, file.name, processed.get(), total)
                                    processFile(file, toProcess, inspectionOffset)
                                    processed.incrementAndGet()
                                } catch (e: Exception) {
                                    MetricService.getInstance(project).error(e)
                                    handleError(e)
                                }
                            },  processed.get() * 200L, TimeUnit.MILLISECONDS)
                        }
                    }, AppExecutorUtil.getAppExecutorService())
            }

            CompletableFuture
                .allOf(*allFutures.toTypedArray())
                .whenComplete { _, err ->
                    if (err is ProcessCanceledException) {
                        MetricService.getInstance(project).error(err)
                        handleCancellation()
                    } else if (err != null) {
                        MetricService.getInstance(project).error(err)
                        handleError(err)
                    } else {
                        onComplete()
                    }
                }
                .join()

        } catch (e: ProcessCanceledException) {
            MetricService.getInstance(project).error(e)
            handleCancellation()
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

    private fun processFile(
        file: PsiFile,
        relatedFiles: List<PsiFile>,
        inspectionOffset: Int
    ): AnalysisResult {
        return OpenAIClient
            .getInstance(project)
            .analyzeFile(
                file,
                relatedFiles,
                inspectionOffset = inspectionOffset
            )
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
    }

    private fun handleCancellation() {
        application.invokeLater {
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

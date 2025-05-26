package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.task

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.AnalysisResult
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi.PsiCrawler
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
            processFiles(getOpenedFiles(project), indicator)
        } catch (e: ProcessCanceledException) {
            MetricService.getInstance(project).error(e)
            handleCancellation()
        } catch (e: Exception) {
            MetricService.getInstance(project).error(e)
            handleError(e)
        }
    }

    private fun getOpenedFiles(project: Project): List<PsiFile> {
        val psiManager = PsiManager.getInstance(project)
        val fileEditorManager = FileEditorManager.getInstance(project)
        val virtualFiles = fileEditorManager.openFiles

        return virtualFiles.mapNotNull { vf -> psiManager.findFile(vf) }
    }

    private fun processFiles(
        files: List<PsiFile>,
        indicator: ProgressIndicator
    ) {
        val total = files.size
        val processed = AtomicInteger(0)
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        val delayMillis = 300L
        var currentDelay = 0L

        val processedFiles = mutableSetOf<PsiFile>()
        for (file in files) {
            indicator.checkCanceled()

            val relatedFiles = PsiCrawler.getInstance(project).getFiles(file).filterNot { processedFiles.contains(it) }
            if (processedFiles.contains(file) || relatedFiles.isEmpty()) {
                continue
            }

            scheduler.schedule({
                try {
                    updateProgress(indicator, file.name, processed.get(), total)
                    processFile(file, inspectionOffset)
                    processed.incrementAndGet()
                } catch (e: Exception) {
                    MetricService.getInstance(project).error(e)
                    handleError(e)
                }
            }, currentDelay, TimeUnit.MILLISECONDS)

            currentDelay += delayMillis
            processedFiles.addAll(relatedFiles + file)
        }

        onComplete()
    }


    private fun processFile(
        file: PsiFile,
        inspectionOffset: Int
    ): AnalysisResult {
        return application.runReadAction<AnalysisResult> {
            val results = OpenAIClient.getInstance(project).analyzeFile(file, inspectionOffset = inspectionOffset)
            return@runReadAction results
        }
    }

    private fun updateProgress(indicator: ProgressIndicator, fileName: String, processed: Int, total: Int) {
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
            MetricService.getInstance(project).error(e)
            Messages.showErrorDialog(
                "Analysis failed: ${e.message}",
                "Error"
            )
        }
    }
}
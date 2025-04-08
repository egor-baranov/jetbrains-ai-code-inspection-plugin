package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.task

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.intellij.util.application
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RelationsAnalyzerTask(
    private val project: Project,
    private val onProgressUpdate: (String) -> Unit = {},
    private val onComplete: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Task.Backgroundable(project, "Analyzing file relations", true) {

    private val executor = AppExecutorUtil.getAppExecutorService()

    override fun run(indicator: ProgressIndicator) {
        try {
            val relations = readRelations()
            processRelations(relations, indicator)
        } catch (e: ProcessCanceledException) {
            MetricService.getInstance(project).error(e)
            handleCancellation()
        } catch (e: Exception) {
            MetricService.getInstance(project).error(e)
            handleError(e)
        } finally {
            application.invokeLater(onComplete)
        }
    }

    private fun readRelations(): Map<PsiFile, List<PsiFile>> {
        return ReadAction.compute<Map<PsiFile, List<PsiFile>>, Throwable> {
            PsiFileRelationService.getInstance(project).getRelations(project)
        }
    }

    private fun processRelations(relations: Map<PsiFile, List<PsiFile>>, indicator: ProgressIndicator) {
        val total = relations.size
        val processed = AtomicInteger(0)
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        val delayMillis = 300L
        var currentDelay = 0L

        relations.forEach { (file, relatedFiles) ->
            indicator.checkCanceled()

            scheduler.schedule({
                try {
                    updateProgress(indicator, file.name, processed.get(), total)
                    processFileRelation(file, relatedFiles, indicator)
                    processed.incrementAndGet()
                } catch (e: Exception) {
                    MetricService.getInstance(project).error(e)
                    handleError(e)
                }
            }, currentDelay, TimeUnit.MILLISECONDS)

            currentDelay += delayMillis
        }
    }


    private fun processFileRelation(file: PsiFile, relatedFiles: List<PsiFile>, indicator: ProgressIndicator) {
        application.runReadAction {
            val codeFiles = createCodeFiles(file, relatedFiles)
            analyzeWithOpenAI(codeFiles, indicator)
        }
    }

    private fun createCodeFiles(file: PsiFile, relatedFiles: List<PsiFile>): List<InspectionService.CodeFile> {
        return (listOf(file) + relatedFiles).map {
            InspectionService.CodeFile(it.virtualFile.url, it.text)
        }
    }

    private fun analyzeWithOpenAI(codeFiles: List<InspectionService.CodeFile>, indicator: ProgressIndicator) {
        val results = OpenAIClient.getInstance(project).analyzeFiles(codeFiles)
        application.invokeLater {
            handleAnalysisResults(results)
        }
    }

    private fun handleAnalysisResults(results: OpenAIClient.AnalysisResult) {
        println("Analyze results size: ${results.content?.length}")
        onProgressUpdate("Processed results with ${results.actions.size} actions")
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
            println("Analysis cancelled by user")
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

    companion object {
        fun execute(
            project: Project,
            onProgressUpdate: (String) -> Unit = {},
            onComplete: () -> Unit = {},
            onError: (Throwable) -> Unit = {}
        ): Task = RelationsAnalyzerTask(project, onProgressUpdate, onComplete, onError).also {
            it.queue()
        }
    }
}
package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.task

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.AnalysisResult
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
    private val inspectionOffset: Int = 3,
    private val onProgressUpdate: (String) -> Unit = {},
    private val onComplete: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Task.Backgroundable(project, "Analyzing file relations", true) {

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
        }
    }

    private fun readRelations(): Map<PsiFile, List<PsiFile>> {
        return ReadAction.compute<Map<PsiFile, List<PsiFile>>, Throwable> {
            PsiFileRelationService.getInstance(project).getRelations(project)
        }
    }

    private fun processRelations(
        relations: Map<PsiFile, List<PsiFile>>,
        indicator: ProgressIndicator
    ) {
        val total = relations.size
        val processed = AtomicInteger(0)
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        val delayMillis = 300L
        var currentDelay = 0L

        val inspectedFiles = InspectionService.getInstance(project).getAffectedFiles().map {
            it.path
        }.toSet()

        val entities = relations.entries.filterNot {
            inspectedFiles.contains(it.key.virtualFile.url)
        }.associate { it.key to it.value }

        val processedFiles = mutableSetOf<PsiFile>()
        for (file in entities.map { it.key }) {
            indicator.checkCanceled()

            val relatedFiles = entities[file].orEmpty().filterNot { processedFiles.contains(it) }
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
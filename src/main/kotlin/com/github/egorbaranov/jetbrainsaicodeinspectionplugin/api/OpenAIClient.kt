package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.Action
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.AnalysisResult
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.ToolCall
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.Message
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.OpenAIResponse
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler.AddInspectionHandler
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler.ApplyInspectionHandler
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler.RequestContextHandler
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings.PluginSettingsState
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import java.util.UUID
import javax.xml.bind.ValidationException

@Service(Service.Level.PROJECT)
class OpenAIClient(
    private val project: Project
) {

    fun analyzeFile(
        file: PsiFile,
        relatedFiles: List<PsiFile>,
        inspectionOffset: Int
    ): AnalysisResult {
        val inspectionId = UUID.randomUUID()
        InspectionService.getInstance(project).inspectionLoading(inspectionId)
        val result = try {
            var toolCall: AnalysisResult? = null
            for (step in 1..PluginSettingsState.getInstance().indexingSteps) {
                val files = (listOf(file) + relatedFiles.take(step * 3)).toSet().map {
                    InspectionService.CodeFile(it.virtualFile.url, it.text)
                }

                val inspections = InspectionService.getInstance(project).getInspections()
                val messages = createMessages(
                    files,
                    inspections
                )

                val tools = ToolsProvider.createTools()
                val response = RestApiClient.getInstance(project).executeRequest(messages, tools)

                toolCall = processToolCalls(inspectionId, response, files, inspectionOffset)
                if (toolCall.actions.all { it !is Action.RequestContext }) {
                    break
                }
            }

            toolCall ?: AnalysisResult()
        } catch (e: Exception) {
            logger.warn("Analysis failed", e)
            MetricService.getInstance(project).error(e)
            AnalysisResult(error = "Analysis failed: ${e.message}")
        }

        return result
    }

    fun performFix(
        inspection: InspectionService.Inspection,
        codeFiles: List<InspectionService.CodeFile>
    ): List<InspectionService.CodeFile> {
        return codeFiles.mapNotNull { codeFile ->
            try {
                val maxAttempts = PluginSettingsState.getInstance().retryQuantity
                var attempts = 0
                var validFixFound = false
                var fixedContent = codeFile.content

                while (attempts < maxAttempts && !validFixFound) {
                    try {
                        val messages = listOf(
                            Message(
                                role = "system",
                                content = """
                        CRITICAL INSTRUCTIONS (NON-NEGOTIABLE):
                        1. Apply EXACTLY: ${inspection.fixPrompt}
                        2. Respond ONLY with raw, unmodified code
                        3. STRICT FORBIDDEN ELEMENTS:
                           - No markdown (```)
                           - No natural language
                           - No code comments
                           - No version headers
                        4. Formatting MUST PRESERVE:
                           - Original indentation
                           - Existing whitespace
                           - Line breaks
                           - Comment positions
                        5. IF YOU CAN'T APPLY INSTRUCTIONS TO A FILE JUST RETURN IT UNCHANGED

                        VIOLATIONS WILL CAUSE AUTOMATIC REJECTION
                    """.trimIndent()),
                            Message(
                                role = "user",
                                content = """
                                ${codeFile.content}
                            """.trimIndent()
                            )
                        )

                        val response = RestApiClient.getInstance(project).executeRequest(messages, emptyList())
                        val rawResponse = response.choices?.firstOrNull()?.message?.content ?: ""

                        fixedContent = rawResponse
                        if (codeFile.content.trim().trimIndent() != fixedContent.trim()
                                .trimIndent() && fixedContent.isNotBlank()
                        ) {
                            validFixFound = true
                        } else {
                            attempts++
                            logger.warn("Structural validation failed, retrying... (${maxAttempts - attempts} remaining)")
                        }
                    } catch (e: ValidationException) {
                        attempts++
                        MetricService.getInstance(project).error(e)
                        logger.warn("Response validation failed: ${e.message} (${maxAttempts - attempts} attempts remaining)")
                    } catch (e: Exception) {
                        attempts++
                        MetricService.getInstance(project).error(e)
                        logger.warn("API request failed: ${e.message} (${maxAttempts - attempts} attempts remaining)")
                    }
                }

                if (validFixFound) codeFile.copy(content = fixedContent.trim()) else null
            } catch (e: Exception) {
                MetricService.getInstance(project).error(e)
                logger.warn("Critical failure fixing ${codeFile.path}", e)
                codeFile
            }
        }
    }

    fun createMessages(
        files: List<InspectionService.CodeFile>,
        existingInspections: List<InspectionService.Inspection>
    ): List<Message> {
        val filesContent = files.joinToString("\n\n") { file ->
            "// File: ${file.path}\n${file.content}"
        }

        val inspectionsContext = if (existingInspections.isNotEmpty()) {
            "Existing Inspections:\n${
                existingInspections.joinToString("\n") {
                    "[${it.id}] ${it.description} - Fix: ${it.fixPrompt}"
                }
            }"
        } else "No existing inspections"

        return listOf(
            Message(
                role = "system",
                content = """
                    Analyze code for improvements using provided tools.
                    $inspectionsContext
                    Available files:
                    $filesContent
                """.trimIndent()
            ),
            Message(
                role = "user",
                content = "Analyze these files and provide improvements using the available tools"
            )
        )
    }

    fun processToolCalls(
        inspectionId: UUID,
        response: OpenAIResponse,
        files: List<InspectionService.CodeFile>,
        inspectionOffset: Int
    ): AnalysisResult {
        val toolResults = response.choices?.firstOrNull()?.message?.toolCalls
            ?.mapNotNull { toolCall: ToolCall ->
                try {
                    when (toolCall.function.name) {
                        "add_inspection" -> AddInspectionHandler(project).handleAddInspection(
                            inspectionId = inspectionId,
                            toolCall.function.arguments,
                            files,
                            inspectionOffset
                        )

                        "apply_inspection" -> ApplyInspectionHandler(project).handleApplyInspection(
                            inspectionId = inspectionId,
                            toolCall.function.arguments,
                            files
                        )

                        "request_context" -> RequestContextHandler().handleRequestContext(
                            toolCall.function.arguments
                        )

                        else -> null
                    }
                } catch (e: Exception) {
                    MetricService.getInstance(project).error(e)
                    logger.warn("Failed to process tool call", e)
                    Action.Error("Failed to process ${toolCall.function.name}: ${e.message}")
                }
            } ?: emptyList()

        return AnalysisResult(
            content = response.choices?.firstOrNull()?.message?.content,
            actions = toolResults,
            error = response.error?.message
        )
    }


    companion object {

        fun getInstance(project: Project): OpenAIClient = project.service()

        private val logger = Logger.getInstance(OpenAIClient::class.java)
    }
}
package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings.PluginSettingsState
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.http.RateLimitingInterceptor
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi.PsiCrawler
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.xml.bind.ValidationException

@Service(Service.Level.PROJECT)
class OpenAIClient(private val project: Project) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(RateLimitingInterceptor(requestsPerMinute = 300))
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val logger = Logger.getInstance(javaClass)

    fun analyzeFile(
        file: PsiFile,
        inspectionOffset: Int
    ): AnalysisResult {
        println("Process file: $file")
        val crawler = PsiCrawler(project)
        val result = try {
            var toolCall: AnalysisResult? = null

            for (step in 1..3) {
                val relatedFiles = crawler.getFiles(file, step * 3)
                val files = createCodeFiles(file, relatedFiles).toMutableList()
                println("PROCESSING FILES: ${files.map { it.path }}")

                val inspections = InspectionService.getInstance(project).getInspections()
                println("inspections size: ${inspections.size}")
                val messages = createMessages(
                    files,
                    inspections
                )

                val tools = createTools(inspections.size < inspectionOffset)
                println("tools: ${tools.size}")
                val response = executeOpenAIRequest(messages, tools)

                toolCall = processToolCalls(response, files, inspectionOffset)
                if (toolCall.actions.all { it is Action.RequestContext }) {
                    println("No request context found: ${toolCall.actions}")
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
                val maxAttempts = 3
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
                    """.trimIndent()
                            ),
                            Message(
                                role = "user",
                                content = """
                                ${codeFile.content}
                            """.trimIndent()
                            )
                        )

                        val response = executeOpenAIRequest(messages, emptyList())
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

    private fun createMessages(
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

    private fun createCodeFiles(file: PsiFile, relatedFiles: List<PsiFile>): List<InspectionService.CodeFile> {
        return (listOf(file) + relatedFiles).toSet().map {
            InspectionService.CodeFile(it.virtualFile.url, it.text)
        }
    }

    private fun createTools(useAddInspectionTool: Boolean): List<JsonObject> = listOfNotNull(
        createTool(
            name = "add_inspection",
            description = "Create new code inspection representing fix or improvement for all analyzed files",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add(
                        "description",
                        createStringSchema("Short description of the issue that should be less than 80 symbols")
                    )
                    add("fix_prompt", createStringSchema("Detailed instructions to fix the issue in a prompt format"))
                })
                add("required", gson.toJsonTree(listOf("description", "fix_prompt")))
            }
        ).takeIf { useAddInspectionTool },
        createTool(
            name = "apply_inspection",
            description = "Apply existing inspection to current files",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("inspection_id", createStringSchema("ID of existing inspection to apply"))
                })
                add("required", gson.toJsonTree(listOf("inspection_id")))
            }
        ),
        createTool(
            name = "request_context",
            description = "Request additional context needed for analysis",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("context_type", createStringSchema("Type of context needed (e.g., imports, dependencies)"))
                })
                add("required", gson.toJsonTree(listOf("context_type")))
            }
        )
    )

    private fun createTool(name: String, description: String, parameters: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("parameters", parameters)
            })
        }
    }

    private fun createStringSchema(description: String): JsonObject {
        return JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", description)
        }
    }

    private fun executeOpenAIRequest(messages: List<Message>, tools: List<JsonObject>): OpenAIResponse {
        val requestBody = ChatRequest(
            model = "gpt-4o",
            messages = messages,
            tools = tools,
            tool_choice = "auto"
        )

        val jsonBody = gson.toJson(requestBody)
        logger.debug("Request body: $jsonBody")

        val request = Request.Builder()
            .url(PluginSettingsState.getInstance().apiUrl)
            .addHeader("Authorization", "Bearer ${PluginSettingsState.getInstance().apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody())
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn("Request failed: ${response.code} ${response.message}")
                throw IOException("Request failed: ${response.code} ${response.message}")
            }

            val responseBody = response.body?.string() ?: throw IOException("Empty response body")
            println("Raw response: $responseBody")
            logger.debug("Raw response: $responseBody")

            gson.fromJson(responseBody, OpenAIResponse::class.java).also {
                it.error?.let { error -> throw IOException("API error: ${error.message}") }
            }
        }
    }

    private fun processToolCalls(
        response: OpenAIResponse,
        files: List<InspectionService.CodeFile>,
        inspectionOffset: Int
    ): AnalysisResult {
        println("process tool calls: ${response.choices?.firstOrNull()?.message?.tool_calls}")
        val toolResults = response.choices?.firstOrNull()?.message?.tool_calls
            ?.mapNotNull { toolCall ->
                try {
                    when (toolCall.function.name) {
                        "add_inspection" -> handleAddInspection(toolCall.function.arguments, files, inspectionOffset)
                        "apply_inspection" -> handleApplyInspection(toolCall.function.arguments, files)
                        "request_context" -> handleRequestContext(toolCall.function.arguments)
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

    private fun handleAddInspection(
        arguments: String,
        files: List<InspectionService.CodeFile>,
        inspectionOffset: Int
    ): Action? {
        println("inspections size: ${InspectionService.getInstance(project).inspectionsById.size}, files size: ${files.size}")
        if (InspectionService.getInstance(project).inspectionFiles.size >= inspectionOffset) {
            println(
                "Max inspection limit (${inspectionOffset}) exceeded, " +
                        "current is ${InspectionService.getInstance(project).inspectionFiles.size}"
            )

            InspectionService.getInstance(project).cancelInspection()
            return null
        }

        val args = gson.fromJson(arguments, AddInspectionArgs::class.java)
        val inspection = InspectionService.Inspection(
            id = UUID.randomUUID().toString(),
            description = args.description,
            fixPrompt = args.fix_prompt
        )

        InspectionService.getInstance(project).putInspection(inspection, files)
        return Action.AddInspection(inspection)
    }

    private fun handleApplyInspection(
        arguments: String,
        files: List<InspectionService.CodeFile>
    ): Action {
        val args = gson.fromJson(arguments, ApplyInspectionArgs::class.java)
        println("Apply inspection: $args, ${files.size}")
        val inspection = InspectionService.getInstance(project).getInspectionById(args.inspection_id)
            ?: return Action.Error("Inspection not found: ${args.inspection_id}")
        InspectionService.getInstance(project).addFilesToInspection(inspection, files)
        return Action.ApplyInspection(inspection)
    }

    private fun handleRequestContext(arguments: String): Action {
        val args = gson.fromJson(arguments, RequestContextArgs::class.java)
        println("requesting extra context: $arguments")
        return Action.RequestContext(contextType = args.context_type)
    }

    sealed class Action {
        data class AddInspection(val inspection: InspectionService.Inspection) : Action()
        data class ApplyInspection(val inspection: InspectionService.Inspection) : Action()
        data class RequestContext(val contextType: String) : Action()
        data class Error(val message: String) : Action()
    }

    data class AnalysisResult(
        val content: String? = null,
        val actions: List<Action> = emptyList(),
        val error: String? = null
    )

    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val tools: List<JsonObject>? = null,
        @SerializedName("tool_choice") val tool_choice: String? = null
    )

    private data class Message(
        val role: String,
        val content: String,
        @SerializedName("tool_calls") val tool_calls: List<ToolCall>? = null
    )

    private data class ToolCall(
        val id: String,
        val type: String,
        val function: FunctionCall
    )

    private data class FunctionCall(
        val name: String,
        val arguments: String
    )

    private data class OpenAIResponse(
        val choices: List<Choice>?,
        val error: OpenAIError?
    )

    private data class Choice(
        val message: Message
    )

    private data class OpenAIError(
        val message: String,
        val type: String,
        val param: String?,
        val code: String?
    )

    // Argument classes
    private data class AddInspectionArgs(
        val description: String,
        @SerializedName("fix_prompt") val fix_prompt: String
    )

    private data class ApplyInspectionArgs(
        @SerializedName("inspection_id") val inspection_id: String
    )

    private data class RequestContextArgs(
        @SerializedName("context_type") val context_type: String
    )

    companion object {
        fun getInstance(project: Project): OpenAIClient {
            return project.service()
        }
    }
}
package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.storage.OpenAIKeyStorage
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.StringUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*

@Service(Service.Level.PROJECT)
class OpenAIClient(private val project: Project) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val logger = Logger.getInstance(javaClass)

    fun analyzeFiles(
        files: List<InspectionService.CodeFile>
    ): AnalysisResult {
        val messages = createMessages(
            files,
            InspectionService.getInstance(project).getInspections()
        )
        val tools = createTools()

        return try {
            val response = executeOpenAIRequest(messages, tools)
            processToolCalls(response, files)
        } catch (e: Exception) {
            logger.error("Analysis failed", e)
            AnalysisResult(error = "Analysis failed: ${e.message}")
        }
    }

    fun performFix(
        inspection: InspectionService.Inspection,
        codeFiles: List<InspectionService.CodeFile>
    ): List<InspectionService.CodeFile> {
        return codeFiles.map { codeFile ->
            try {
                val messages = listOf(
                    Message(
                        role = "system",
                        content = """
                        Apply this fix: ${inspection.fixPrompt}
                        - Return only the fixed code
                        - No explanations or markdown
                        - Preserve original formatting
                    """.trimIndent()
                    ),
                    Message(
                        role = "user",
                        content = "Original code:\n${codeFile.content}"
                    )
                )

                // Call without tools parameter
                val response = executeOpenAIRequest(messages, emptyList())
                val fixedContent = StringUtils.extractCode(response.choices?.firstOrNull()?.message?.content!!)

                codeFile.copy(content = fixedContent)
            } catch (e: Exception) {
                logger.error("Fix failed for ${codeFile.path}", e)
                codeFile  // Return original file on error
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

    private fun createTools(): List<JsonObject> = listOf(
        createTool(
            name = "add_inspection",
            description = "Create new code inspection finding for all analyzed files",
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
        ),
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
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${getApiKey()}")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody())
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Request failed: ${response.code} ${response.message}")
            }

            val responseBody = response.body?.string() ?: throw IOException("Empty response body")
            logger.debug("Raw response: $responseBody")

            gson.fromJson(responseBody, OpenAIResponse::class.java).also {
                it.error?.let { error -> throw IOException("API error: ${error.message}") }
            }
        }
    }

    private fun processToolCalls(
        response: OpenAIResponse,
        files: List<InspectionService.CodeFile>
    ): AnalysisResult {
        val toolResults = response.choices?.firstOrNull()?.message?.tool_calls
            ?.mapNotNull { toolCall ->
                try {
                    when (toolCall.function.name) {
                        "add_inspection" -> handleAddInspection(toolCall.function.arguments, files)
                        "apply_inspection" -> handleApplyInspection(toolCall.function.arguments, files)
                        "request_context" -> handleRequestContext(toolCall.function.arguments)
                        else -> null
                    }
                } catch (e: Exception) {
                    logger.error("Failed to process tool call", e)
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
        files: List<InspectionService.CodeFile>
    ): Action {
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
        println("Apply inspection: $args")
        val inspection = InspectionService.getInstance(project).getInspectionById(args.inspection_id)
            ?: return Action.Error("Inspection not found: ${args.inspection_id}")
        InspectionService.getInstance(project).addFilesToInspection(inspection, files)
        return Action.ApplyInspection(inspection)
    }

    private fun handleRequestContext(arguments: String): Action {
        val args = gson.fromJson(arguments, RequestContextArgs::class.java)
        return Action.RequestContext(contextType = args.context_type)
    }

    private fun getApiKey(): String = ApplicationManager.getApplication()
        .getService(OpenAIKeyStorage::class.java)
        .apiKey

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

    // OpenAI API data classes
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
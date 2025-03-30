package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.OpenAIKeyStorage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OpenAIClient {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val logger = Logger.getInstance(javaClass)

    fun getSuggestions(context: CodeContext): String {
        val prompt = """
            Analyze this ${context.language} code and suggest improvements:
            - Code Structure:
            ${context.structure}
            
            - Complexity Factors:
            ${context.complexityFactors.joinToString("\n")}
            
            - Code:
            ${context.codeSnippet}
            
            Provide 3 suggestions to:
            1. Reduce cyclomatic complexity
            2. Improve maintainability
            3. Follow language best practices
            """.trimIndent()

        return try {
            request(prompt)
        } catch (e: Exception) {
            logger.error("Failed to get suggestions", e)
            "Error generating suggestions: ${e.message}"
        }
    }

    fun request(prompt: String): String {
        val apiKey = getApiKey()
        val requestBody = ChatRequest(
            model = "gpt-4o",
            messages = listOf(Message(role = "user", content = prompt)),
            temperature = 0.7
        )

        val jsonBody = gson.toJson(requestBody)
        println("Request body: $jsonBody")

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }

            val jsonResponse = response.body?.string() ?: throw IOException("Empty response body")
            logger.debug("Raw response: $jsonResponse")

            val completion = gson.fromJson(jsonResponse, OpenAIResponse::class.java)

            completion.error?.let { error ->
                throw IOException("API error: ${error.message} (type: ${error.type})")
            }

            return completion.choices?.firstOrNull()?.message?.content
                ?: "No suggestions available"
        }
    }

    private fun getApiKey(): String {
        return ApplicationManager.getApplication()
            .getService(OpenAIKeyStorage::class.java)
            .apiKey
    }

    // Request/Response data classes
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double
    )

    private data class Message(
        val role: String,
        val content: String
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
        @SerializedName("code") val errorCode: String?
    )
}

data class CodeContext(
    val language: String,
    val codeSnippet: String,
    val structure: String,
    val complexityFactors: List<String>
)
package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAIClient(private val apiKey: String) {
    private val client = OkHttpClient()
    private val gson = Gson()

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

        val requestBody = """
        {
            "model": "gpt-3.5-turbo",
            "messages": [{"role": "user", "content": "$prompt"}],
            "temperature": 0.7
        }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody())
            .build()

        val response = client.newCall(request).execute()
        val jsonResponse = response.body?.string()
        val completion = gson.fromJson(jsonResponse, OpenAIResponse::class.java)

        return completion.choices.firstOrNull()?.message?.content ?: "No suggestions available"
    }

    // Data classes must be inside the class or in separate files
    private data class OpenAIResponse(val choices: List<Choice>)
    private data class Choice(val message: Message)
    private data class Message(val content: String)
}

// Add this in the same file or in a separate CodeContext.kt file
data class CodeContext(
    val language: String,
    val codeSnippet: String,
    val structure: String,
    val complexityFactors: List<String>
)

package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.Message
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.OpenAIRequest
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.OpenAIResponse
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings.PluginSettingsState
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.http.RateLimitingInterceptor
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class RestApiClient {

    private val client = OkHttpClient.Builder()
        .addInterceptor(RateLimitingInterceptor(requestsPerMinute = 300))
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val logger = Logger.getInstance(javaClass)

    fun executeRequest(messages: List<Message>, tools: List<JsonObject>): OpenAIResponse {
        val requestBody = OpenAIRequest(
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
}
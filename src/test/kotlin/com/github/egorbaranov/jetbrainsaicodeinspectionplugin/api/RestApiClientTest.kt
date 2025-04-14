package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.Message
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.OpenAIResponse
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings.PluginSettingsState
import com.google.gson.JsonObject
import com.intellij.testFramework.LightPlatformTestCase
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import okhttp3.*
import okhttp3.Call
import okio.Buffer
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException

@ExtendWith(MockKExtension::class)
class RestApiClientTest : LightPlatformTestCase() {
    @MockK
    private var mockCall: Call = mockk()

    @MockK
    private var mockSettings: PluginSettingsState = mockk()

    private lateinit var client: OkHttpClient
    private lateinit var restApiClient: RestApiClient
    private val requestSlot = slot<Request>()
    private val messages = listOf(Message("user", "test"))
    private val tools = listOf(JsonObject())

    override fun setUp() {
        super.setUp()
        // Configure mock settings
        every { mockSettings.apiUrl } returns "http://test-url"
        every { mockSettings.apiKey } returns "test-key"

        // Configure HTTP client mock
        client = mockk {
            every { newCall(capture(requestSlot)) } returns mockCall
        }

        // Create SUT with mocked dependencies
        restApiClient = RestApiClient(
            pluginSettingsState = mockSettings,
            client = client
        )
    }

    fun `testSuccessful response returns parsed object`() {
        mockResponse(true, 200, """{"id":"test","choices":[]}""")

        val result = restApiClient.executeRequest(messages, tools)

        assertEquals(OpenAIResponse(emptyList(), null), result)
        assertEquals(0, result.choices?.size)
    }

    fun `testThrows on unsuccessful status code`() {
        mockResponse(false, 500, "Error message")

        val ex = assertThrows<IOException> {
            restApiClient.executeRequest(messages, tools)
        }
        assertEquals("Request failed: 500 Message", ex.message)
    }

    fun `testThrows on empty response body`() {
        val mockResponse: Response = mockk()
        every { mockResponse.isSuccessful } returns true
        every { mockResponse.body } returns null
        every { mockCall.execute() } returns mockResponse

        val ex = assertThrows<IOException> {
            restApiClient.executeRequest(messages, tools)
        }
        assertEquals("Empty response body", ex.message)
    }

    fun `testThrows on API error in response`() {
        mockResponse(true, 200, """{"error":{"message":"API error"}}""")

        val ex = assertThrows<IOException> {
            restApiClient.executeRequest(messages, tools)
        }
        assertEquals("API error: API error", ex.message)
    }

    fun `testBuilds correct request structure`() {
        mockResponse(true, 200, """{"id":"test"}""")

        restApiClient.executeRequest(messages, tools)

        val request = requestSlot.captured
        assertEquals("http://test-url/", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("application/json", request.header("Content-Type"))

        val expectedBody =
            """{"model":"gpt-4o","messages":[{"role":"user","content":"test"}],"tools":[{}],"tool_choice":"auto"}"""
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        assertEquals(expectedBody, buffer.readUtf8())
    }

    private fun mockResponse(success: Boolean, code: Int, body: String) {
        val mockResponse: Response = mockk()
        val mockBody: ResponseBody = mockk()

        every { mockCall.execute() } returns mockResponse
        every { mockResponse.isSuccessful } returns success
        every { mockResponse.code } returns code
        every { mockResponse.message } returns "Message"
        every { mockResponse.body } returns mockBody
        every { mockBody.string() } returns body
        every { mockResponse.close() } just Runs
    }
}
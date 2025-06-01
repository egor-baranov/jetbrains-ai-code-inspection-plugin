package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.Action
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.ToolCall
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.Message
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.OpenAIResponse
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.rd.util.ConcurrentHashMap
import io.mockk.*
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import javax.xml.bind.ValidationException

@ExtendWith(MockKExtension::class)
class OpenAIClientTest : BasePlatformTestCase() {

    private lateinit var openAIClient: OpenAIClient
    private val mockProject = mockk<Project>(relaxed = true)
    private val mockRestApiClient = mockk<RestApiClient>(relaxed = true)
    private val mockInspectionService = mockk<InspectionService>()
    private val mockMetricService = mockk<MetricService>()
    private val mockPsiFile = mockk<PsiFile>()
    private val mockVirtualFile = mockk<VirtualFile>()

    override fun setUp() {
        super.setUp()

        mockkStatic(
            InspectionService::class,
            MetricService::class,
            RestApiClient::class
        )

        // Service instance mocks
        every { InspectionService.getInstance(mockProject) } returns mockInspectionService
        every { MetricService.getInstance(mockProject) } returns mockMetricService
        every { RestApiClient.getInstance(mockProject) } returns mockRestApiClient

        // Service method stubs
        every { mockInspectionService.getInspections() } returns emptyList()
        every { mockInspectionService.inspectionFiles } returns ConcurrentHashMap()
        every { mockInspectionService.putInspection(any(), any()) } just Runs
        every { mockInspectionService.addFilesToInspection(any(), any()) } just Runs

        every { mockPsiFile.project } returns mockProject
        every { mockPsiFile.virtualFile } returns mockVirtualFile
        every { mockPsiFile.text } returns "text"
        every { mockVirtualFile.url } returns "sample url"

        every { mockMetricService.error(any()) } just Runs

        openAIClient = OpenAIClient(mockProject)
    }

    fun `test analyzeFile should return empty result when no tool calls`() {
        val response = OpenAIResponse(
            choices = listOf(OpenAIResponse.Choice(Message(role = "assistant", content = "test"))),
            error = null
        )
        every { mockRestApiClient.executeRequest(any(), any()) } returns response

        val result = openAIClient.analyzeFile(mockPsiFile, emptyList(), 3)

        assertTrue(result.actions.isEmpty())
        assertEquals("test", result.content)
    }

    fun `testAnalyzeFile should handle exceptions gracefully`() {
        every { mockRestApiClient.executeRequest(any(), any()) } throws RuntimeException("API error")

        val result = openAIClient.analyzeFile(mockPsiFile, emptyList(), 3)

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("API error"))
        verify { mockMetricService.error(any<RuntimeException>()) }
    }

    fun `test performFix should return modified files on successful fix`() {
        val codeFile = InspectionService.CodeFile("test.txt", "original")
        val inspection = InspectionService.Inspection()
        val mockResponse = OpenAIResponse(
            choices = listOf(
                OpenAIResponse.Choice(Message("assistant", "fixed")),
            ),
            error = null
        )
        every { mockRestApiClient.executeRequest(any(), any()) } returns mockResponse

        val result = openAIClient.performFix(inspection, listOf(codeFile))
        assertEquals("fixed", result[0].content)
    }

    fun `testPerformFix should return original file after max attempts`() {
        val codeFile = InspectionService.CodeFile("test.txt", "original")
        every { mockRestApiClient.executeRequest(any(), any()) } throws
                RuntimeException(ValidationException("invalid"))

        val mockInspection = mockk<InspectionService.Inspection>()
        every { mockInspection.fixPrompt } returns "fixPrompt"

        val result = openAIClient.performFix(mockInspection, listOf(codeFile))

        assertTrue(result.isEmpty())
        verify(exactly = 3) { mockRestApiClient.executeRequest(any(), any()) }
    }

    fun `testProcessToolCalls should handle different tool types`() {
        val toolCalls = listOf(
            ToolCall(
                id = "1",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "add_inspection",
                    arguments = """{"description":"Test","fix_prompt":"Fix"}"""
                )
            ),
            ToolCall(
                id = "2",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "apply_inspection",
                    arguments = """{"inspection_id":"123"}"""
                )
            ),
            ToolCall(
                id = "3",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "request_context",
                    arguments = """{"context_type":"classes"}"""
                )
            ),
            ToolCall(
                id = "4",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "apply_inspection",
                    arguments = """{"inspection_id":"234"}"""
                )
            ),
            ToolCall(
                id = "5",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "unknown",
                    arguments = "test"
                )
            )
        )

        val response = OpenAIResponse(
            choices = listOf(OpenAIResponse.Choice(Message("assistant", "test", toolCalls))),
            error = null
        )

        every { mockInspectionService.getInspectionById("123") } returns mockk()

        val result = openAIClient.processToolCalls(UUID.randomUUID(), response, emptyList(), 3)

        assertEquals(4, result.actions.size)
        assertTrue(result.actions[0] is Action.AddInspection)
        assertTrue(result.actions[1] is Action.ApplyInspection)
        assertTrue(result.actions[2] is Action.RequestContext)
        assertTrue(result.actions[3] is Action.Error)
    }
}
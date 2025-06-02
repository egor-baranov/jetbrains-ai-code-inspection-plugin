package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.Action
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.ToolCall
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.Message
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai.OpenAIResponse
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler.AddInspectionHandler
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler.ApplyInspectionHandler
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.handler.RequestContextHandler
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings.PluginSettingsState
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.*
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import javax.xml.bind.ValidationException

@ExtendWith(MockKExtension::class)
class OpenAIClientTest : BasePlatformTestCase() {

    private lateinit var openAIClient: OpenAIClient
    private val mockRestApiClient = mockk<RestApiClient>(relaxed = true)
    private val mockInspectionService = mockk<InspectionService>(relaxed = true)
    private val mockMetricService = mockk<MetricService>(relaxed = true)
    private val mockSettings = mockk<PluginSettingsState>(relaxed = true)
    private val mockPsiFile = mockk<PsiFile>(relaxed = true)
    private val mockVirtualFile = mockk<VirtualFile>(relaxed = true)

    override fun setUp() {
        super.setUp()

        // Mock companion objects for getInstance calls
        mockkObject(InspectionService.Companion)
        mockkObject(MetricService.Companion)
        mockkObject(RestApiClient.Companion)
        mockkObject(PluginSettingsState.Companion)

        val testProject = project

        every { InspectionService.getInstance(testProject) } returns mockInspectionService
        every { MetricService.getInstance(testProject) } returns mockMetricService
        every { RestApiClient.getInstance(testProject) } returns mockRestApiClient
        every { PluginSettingsState.getInstance() } returns mockSettings

        every { mockSettings.indexingSteps } returns 1
        every { mockSettings.retryQuantity } returns 2

        every { mockInspectionService.getInspections() } returns emptyList()
        every { mockInspectionService.inspectionLoading(any()) } just Runs
        every { mockInspectionService.putInspection(any(), any()) } just Runs
        every { mockInspectionService.addFilesToInspection(any(), any()) } just Runs

        every { mockPsiFile.project } returns testProject
        every { mockPsiFile.virtualFile } returns mockVirtualFile
        every { mockPsiFile.text } returns "fileContent"
        every { mockVirtualFile.url } returns "file://test.java"

        every { mockMetricService.error(any()) } just Runs

        openAIClient = OpenAIClient(testProject)
    }

    override fun tearDown() {
        unmockkAll()
        super.tearDown()
    }

    fun `test analyzeFile should return empty result when no tool calls`() {
        val response = OpenAIResponse(
            choices = listOf(OpenAIResponse.Choice(Message(role = "assistant", content = "test-content"))),
            error = null
        )
        every { mockRestApiClient.executeRequest(any(), any()) } returns response

        val result = openAIClient.analyzeFile(mockPsiFile, emptyList(), inspectionOffset = 3)

        assertTrue(result.actions.isEmpty())
        assertEquals("test-content", result.content)
    }

    fun `test analyzeFile should handle exceptions gracefully`() {
        every { mockRestApiClient.executeRequest(any(), any()) } throws RuntimeException("API failure")

        val result = openAIClient.analyzeFile(mockPsiFile, emptyList(), inspectionOffset = 5)

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("API failure"))
        verify { mockMetricService.error(ofType<RuntimeException>()) }
    }

    fun `test performFix should return modified files on successful fix`() {
        val codeFile = InspectionService.CodeFile(path = "file.txt", content = "original-content")
        val inspection = InspectionService.Inspection(id = "id", description = "desc", fixPrompt = "apply-fix")

        val mockResponse = OpenAIResponse(
            choices = listOf(OpenAIResponse.Choice(Message(role = "assistant", content = "fixed-content"))),
            error = null
        )
        every { mockRestApiClient.executeRequest(any(), any()) } returns mockResponse

        val result = openAIClient.performFix(inspection, listOf(codeFile))

        assertEquals(1, result.size)
        assertEquals("fixed-content", result[0].content)
    }

    fun `test performFix should return empty when max attempts reached`() {
        val codeFile = InspectionService.CodeFile(path = "file.txt", content = "original-content")
        every { mockRestApiClient.executeRequest(any(), any()) } throws ValidationException("invalid")

        val inspection = InspectionService.Inspection(id = "id", description = "desc", fixPrompt = "apply-fix")

        val result = openAIClient.performFix(inspection, listOf(codeFile))

        assertTrue(result.isEmpty())
        verify(exactly = 3) { mockRestApiClient.executeRequest(any(), any()) }
    }

    fun `test processToolCalls should map tool calls to actions`() {
        val toolCalls = listOf(
            ToolCall(
                id = "1",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "add_inspection",
                    arguments = """{"id":"ins1","description":"D","fix_prompt":"F"}"""
                )
            ),
            ToolCall(
                id = "2",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "apply_inspection",
                    arguments = """{"id":"ins2","description":"D2","fix_prompt":"F2"}"""
                )
            ),
            ToolCall(
                id = "3",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "request_context",
                    arguments = """{"contextType":"ctx"}"""
                )
            ),
            ToolCall(
                id = "4",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "apply_inspection",
                    arguments = """{"id":"ins3","description":"D3","fix_prompt":"F3"}"""
                )
            ),
            ToolCall(
                id = "5",
                type = "function",
                function = ToolCall.FunctionCall(
                    name = "unknown_tool",
                    arguments = "{}"
                )
            )
        )

        val response = OpenAIResponse(
            choices = listOf(OpenAIResponse.Choice(Message(role = "assistant", content = "ignored", toolCalls = toolCalls))),
            error = null
        )

        every { mockRestApiClient.executeRequest(any(), any()) } returns response

        val sampleInspection1 = InspectionService.Inspection(id = "ins1", description = "D", fixPrompt = "F")
        val sampleInspection2 = InspectionService.Inspection(id = "ins2", description = "D2", fixPrompt = "F2")
        val sampleInspection3 = InspectionService.Inspection(id = "ins3", description = "D3", fixPrompt = "F3")

        mockkConstructor(AddInspectionHandler::class)
        every {
            anyConstructed<AddInspectionHandler>().handleAddInspection(
                any(), any(), any(), any()
            )
        } returns Action.AddInspection(sampleInspection1)

        mockkConstructor(ApplyInspectionHandler::class)
        every {
            anyConstructed<ApplyInspectionHandler>().handleApplyInspection(
                any(), match { it.contains("\"id\":\"ins2\"") }, any()
            )
        } returns Action.ApplyInspection(sampleInspection2)
        every {
            anyConstructed<ApplyInspectionHandler>().handleApplyInspection(
                any(), match { it.contains("\"id\":\"ins3\"") }, any()
            )
        } returns Action.ApplyInspection(sampleInspection3)

        mockkConstructor(RequestContextHandler::class)
        every {
            anyConstructed<RequestContextHandler>().handleRequestContext(
                match { it.contains("\"contextType\":\"ctx\"") }
            )
        } returns Action.RequestContext("ctx")

        val result = openAIClient.processToolCalls(UUID.randomUUID(), response, emptyList(), inspectionOffset = 2)

        assertEquals(4, result.actions.size)
        assertTrue(result.actions[0] is Action.AddInspection)
        assertEquals(sampleInspection1, (result.actions[0] as Action.AddInspection).inspection)

        assertTrue(result.actions[1] is Action.ApplyInspection)
        assertEquals(sampleInspection2, (result.actions[1] as Action.ApplyInspection).inspection)

        assertTrue(result.actions[2] is Action.RequestContext)
        assertEquals("ctx", (result.actions[2] as Action.RequestContext).contextType)

        assertTrue(result.actions[3] is Action.ApplyInspection)
        assertEquals(sampleInspection3, (result.actions[3] as Action.ApplyInspection).inspection)
    }
}

package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.*
import org.jdom.Element

class InspectionServiceTest : BasePlatformTestCase() {

    private lateinit var inspectionService: InspectionService

    override fun setUp() {
        super.setUp()

        inspectionService = InspectionService(project)
        inspectionService.clearState()

        mockkObject(OpenAIClient)
        every { OpenAIClient.getInstance(project).performFix(any(), any()) } answers { secondArg<List<InspectionService.CodeFile>>() }
    }

    override fun tearDown() {
        clearAllMocks()
        super.tearDown()
    }

    private fun waitForBackgroundTasks() {
        while (inspectionService.getTasks().isNotEmpty()) {
            Thread.sleep(10)
        }
    }

    fun `test put and get inspection`() {
        val inspection = InspectionService.Inspection(
            id = "1",
            description = "Test Inspection",
            fixPrompt = "Fix it"
        )
        val file = InspectionService.CodeFile(
            path = "/path/to/file",
            content = "content"
        )

        inspectionService.putInspection(inspection, listOf(file))
        waitForBackgroundTasks()

        val retrieved = inspectionService.getInspectionById("1")
        assertNotNull("Inspection should be retrieved", retrieved)
        assertEquals("Inspection description should match", "Test Inspection", retrieved?.description)

        val inspections = inspectionService.getInspections()
        assertTrue("Inspections list should contain the inspection", inspections.contains(inspection))

        val affectedFiles = inspectionService.getAffectedFiles()
        assertTrue("Affected files should contain the file", affectedFiles.any { it.path == file.path })
    }

    fun `test set inspection description`() {
        val inspection = InspectionService.Inspection(
            id = "2",
            description = "Old Description",
            fixPrompt = "Fix"
        )
        val file = InspectionService.CodeFile(
            path = "/path/to/another",
            content = "data"
        )
        inspectionService.putInspection(inspection, listOf(file))
        waitForBackgroundTasks()

        inspectionService.setInspectionDescription("2", "New Description")
        waitForBackgroundTasks()

        val updatedInspection = inspectionService.getInspectionById("2")
        assertNotNull("Updated inspection should not be null", updatedInspection)
        assertEquals("Description should be updated", "New Description", updatedInspection?.description)

        val affectedFiles = inspectionService.getAffectedFiles()
        assertTrue("Code file should still be present", affectedFiles.any { it.path == file.path })
    }

    fun `test add files to inspection and perform fix`() {
        val inspection = InspectionService.Inspection(
            id = "3",
            description = "Inspection for addFiles",
            fixPrompt = "Fix prompt"
        )
        val file = InspectionService.CodeFile(
            path = "/path/to/add",
            content = "original"
        )
        inspectionService.putInspection(inspection, emptyList())
        waitForBackgroundTasks()

        val fixedFiles = listOf(
            InspectionService.CodeFile(path = file.path, content = "fixed content")
        )

        every { OpenAIClient.getInstance(project).performFix(inspection, listOf(file)) } returns fixedFiles

        var onPerformedCallbackFiles: List<InspectionService.CodeFile>? = null
        inspectionService.addFilesToInspection(inspection, listOf(file))

        inspectionService.performFixWithProgress(inspection, listOf(file)) {
            onPerformedCallbackFiles = it
        }
        waitForBackgroundTasks()

        assertNotNull("The fixed file callback should have been invoked", onPerformedCallbackFiles)
        assertEquals(
            "Fixed content should be updated",
            "fixed content",
            onPerformedCallbackFiles?.first()?.content
        )
    }

    fun `test state persistence`() {
        val inspection = InspectionService.Inspection(
            id = "4",
            description = "State Test",
            fixPrompt = "Prompt"
        )
        val file = InspectionService.CodeFile(
            path = "/state/file",
            content = "state content"
        )
        inspectionService.putInspection(inspection, listOf(file))
        waitForBackgroundTasks()

        val state: Element = inspectionService.getState()
        inspectionService.clearState()
        assertTrue("State should be empty after clear", inspectionService.getInspections().isEmpty())

        inspectionService.loadState(state)
        waitForBackgroundTasks()

        val loadedInspection = inspectionService.getInspectionById("4")
        assertNotNull("Loaded inspection should not be null", loadedInspection)
        assertEquals("Loaded inspection description", "State Test", loadedInspection?.description)
        val affectedFiles = inspectionService.getAffectedFiles()
        assertTrue("State loaded should include the file",
            affectedFiles.any { it.path == file.path && it.content == file.content }
        )
    }

    fun `test remove inspection`() {
        val inspection = InspectionService.Inspection(
            id = "5",
            description = "To be removed",
            fixPrompt = "Prompt"
        )
        val file = InspectionService.CodeFile(
            path = "/remove/file",
            content = "content"
        )
        inspectionService.putInspection(inspection, listOf(file))
        waitForBackgroundTasks()

        inspectionService.removeInspection(inspection)
        waitForBackgroundTasks()

        val retrieved = inspectionService.getInspectionById("5")
        assertNull("Inspection should be removed", retrieved)
        val inspections = inspectionService.getInspections()
        assertFalse("Inspections list should not contain removed inspection", inspections.contains(inspection))
    }

    fun `test remove file from inspection`() {
        val inspection = InspectionService.Inspection(
            id = "6",
            description = "Remove file test",
            fixPrompt = "Fix prompt"
        )
        val file1 = InspectionService.CodeFile(
            path = "/file/one",
            content = "content1"
        )
        val file2 = InspectionService.CodeFile(
            path = "/file/two",
            content = "content2"
        )
        inspectionService.putInspection(inspection, listOf(file1, file2))
        waitForBackgroundTasks()

        inspectionService.removeFileFromInspection(inspection, file1)
        waitForBackgroundTasks()

        val affectedFiles = inspectionService.getAffectedFiles()
        assertFalse("File1 should be removed", affectedFiles.any { it.path == file1.path })
        assertTrue("File2 should remain", affectedFiles.any { it.path == file2.path })
    }

    fun testCancelInspection() {
        val listener = spyk(object : InspectionService.InspectionChangeListener {
            override fun inspectionLoading(inspection: InspectionService.Inspection) {}
            override fun inspectionLoaded(inspection: InspectionService.Inspection) {}
            override fun inspectionCancelled() { /* marker callback */ }
            override fun removeInspection(inspection: InspectionService.Inspection) {}
            override fun removeFileFromInspection(
                inspection: InspectionService.Inspection,
                codeFile: InspectionService.CodeFile
            ) {}
        })

        project.messageBus.connect(testRootDisposable)
            .subscribe(InspectionService.INSPECTION_CHANGE_TOPIC, listener)

        inspectionService.cancelInspection()
        waitForBackgroundTasks()

        verify { listener.inspectionCancelled() }
    }
}

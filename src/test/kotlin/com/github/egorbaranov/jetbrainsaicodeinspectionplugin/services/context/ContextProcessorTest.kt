package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.*
import kotlinx.coroutines.*

/**
 * Dummy definition of ProjectIndexer.IndexingHandler.
 * Note that processElement is declared suspend to match how it’s invoked.
 */
private interface ProjectIndexer {
    interface IndexingHandler {
        fun shouldProcess(element: PsiElement): Boolean
        suspend fun processElement(element: PsiElement)
    }
}

class ContextProcessorTest : BasePlatformTestCase() {

    private lateinit var processor: ContextProcessor
    private lateinit var handler: ProjectIndexer.IndexingHandler
    private lateinit var indicator: ProgressIndicator
    private lateinit var scope: CoroutineScope

    override fun setUp() {
        super.setUp()
        processor = ContextProcessor(project)
        // Create a dummy handler with a suspend processElement.
        handler = object : ProjectIndexer.IndexingHandler {
            override fun shouldProcess(element: PsiElement): Boolean = true
            override suspend fun processElement(element: PsiElement) { /* marker */ }
        }
        // Wrap handler in a spy if verification is desired.
        handler = spyk(handler)
        indicator = mockk(relaxed = true)
        // Use the Unconfined dispatcher to run launched coroutines immediately.
        scope = CoroutineScope(Dispatchers.Unconfined)

        // Stub static methods for PSI.
        mockkStatic(ProjectRootManager::class, PsiManager::class, PsiTreeUtil::class)
    }

    override fun tearDown() {
        unmockkStatic(ProjectRootManager::class, PsiManager::class, PsiTreeUtil::class)
        clearAllMocks()
        unmockkAll()
        super.tearDown()
    }

    /**
     * Test getPersistentKey.
     *
     * Instead of stubbing javaClass.name (which isn’t mockable),
     * we stub only methods for containingFile and textOffset.
     */
    fun `test get persistent key`() {
        // Create a fake containing file.
        val fakeContainingFile = mockk<PsiFile>(relaxed = true)
        every { fakeContainingFile.name } returns "FakeFile.kt"

        // Create a fake PsiElement. Do not stub javaClass.name.
        val fakeElement = mockk<PsiElement>(relaxed = true)
        every { fakeElement.containingFile } returns fakeContainingFile
        every { fakeElement.textOffset } returns 123

        // Access the private getPersistentKey(PsiElement) method via reflection.
        val getKeyMethod = ContextProcessor::class.java.getDeclaredMethod("getPersistentKey", PsiElement::class.java)
        getKeyMethod.isAccessible = true
        val key = getKeyMethod.invoke(processor, fakeElement) as String

        // Build the expected key from the actual runtime value.
        val expectedKey = "${fakeElement.javaClass.name}-FakeFile.kt-123"
        assertEquals("Persistent key should match expected value", expectedKey, key)
    }

    /**
     * Test processing a project with one root and elements.
     */
    fun `test process project with elements`() = runBlocking {
        // Create a fake VirtualFile for the root.
        val fakeRoot = mockk<VirtualFile>(relaxed = true)
        every { fakeRoot.url } returns "file://fakeRoot"
        every { fakeRoot.isValid } returns true

        // Stub ProjectRootManager to return our fake root.
        val projectRootManager = mockk<ProjectRootManager>()
        every { ProjectRootManager.getInstance(project) } returns projectRootManager
        every { projectRootManager.contentSourceRoots } returns arrayOf(fakeRoot)

        // Stub PsiManager.findDirectory(fakeRoot) to return a fake PsiDirectory.
        val fakeDirectory = mockk<PsiDirectory>(relaxed = true)
        val psiManager = mockk<PsiManager>(relaxed = true)
        every { PsiManager.getInstance(project) } returns psiManager
        every { psiManager.findDirectory(fakeRoot) } returns fakeDirectory
        every { fakeDirectory.isValid } returns true
        every { fakeDirectory.virtualFile } returns fakeRoot

        // Create a fake PsiFile to act as a child.
        val fakePsiFile = mockk<PsiFile>(relaxed = true)
        every { fakePsiFile.isValid } returns true
        every { fakePsiFile.name } returns "FakeFile.kt"
        val fakeFileVirtual = mockk<VirtualFile>(relaxed = true)
        every { fakeFileVirtual.url } returns "file://FakeFile.kt"
        every { fakeFileVirtual.isValid } returns true
        every { fakePsiFile.virtualFile } returns fakeFileVirtual
        every { fakePsiFile.containingFile } returns fakePsiFile // self-reference for simplicity

        // Stub directory children to include the fake PsiFile.
        every { fakeDirectory.children } returns arrayOf(fakePsiFile)

        // Use a slot to capture the PsiElementProcessor.
        val processorSlot = slot<com.intellij.psi.search.PsiElementProcessor<PsiElement>>()
        // Disambiguate overload by using the type from com.intellij.psi.search.
        every { PsiTreeUtil.processElements(any<PsiElement>(), capture(processorSlot)) } answers {
            // Simulate the processing: call process on our fakePsiFile.
            processorSlot.captured.execute(fakePsiFile)
            true
        }

        // Use coEvery to count invocations of processElement.
        var processedCount = 0
        coEvery { handler.processElement(any()) } coAnswers {
            processedCount++
        }

        // Call processProject. This triggers processing of the fake root/directory/file.
        processor.processProject(handler, indicator, scope)
        // Yield and delay to allow the launched coroutine(s) to complete.
        yield()
        delay(10)

        // Verify that indexData is populated.
        assertFalse("indexData should not be empty after processing", processor.indexData.isEmpty())
        // Verify that the handler’s processElement was called at least once.
        assertTrue("Handler should have processed at least one element", processedCount >= 1)
    }

    /**
     * Test that processProject does nothing when there are no content roots.
     */
    fun `test process project no roots`() {
        val projectRootManager = mockk<ProjectRootManager>()
        every { ProjectRootManager.getInstance(project) } returns projectRootManager
        // Return an empty array of VirtualFiles
        every { projectRootManager.contentSourceRoots } returns emptyArray<VirtualFile>()

        // Calling processProject should not add any data.
        processor.processProject(handler, indicator, scope)
        // We expect no elements added to the indexData map.
        assertTrue("indexData should be empty when no roots exist", processor.indexData.isEmpty())
    }

    /**
     * Verify that calculateProgress works as expected.
     */
    fun `test calculate progress`() {
        // Use reflection to access the private calculateProgress() method.
        val calcMethod = ContextProcessor::class.java.getDeclaredMethod("calculateProgress")
        calcMethod.isAccessible = true

        // When indexData is empty, progress should be 0.0.
        processor.indexData.clear()
        val progressEmpty = calcMethod.invoke(processor) as Double
        assertEquals("Progress should be 0.0 when indexData is empty", 0.0, progressEmpty, 0.0)

        // When one element is added, progress = (1 % 100) / 100.0 = 0.01.
        processor.indexData["dummyKey"] = mockk(relaxed = true)
        val progressOne = calcMethod.invoke(processor) as Double
        assertEquals("Progress should be 0.01 when one element exists", 0.01, progressOne, 0.0)
    }
}

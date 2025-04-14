package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.*
import org.jdom.Element

class PsiFileRelationServiceTest : BasePlatformTestCase() {

    private lateinit var service: PsiFileRelationService

    override fun setUp() {
        super.setUp()
        service = PsiFileRelationService.getInstance(project)
        // Clear previous state.
        service.loadState(Element("PsiFileRelations"))
        // Make sure that all static methods can be stubbed.
        mockkStatic(VirtualFileManager::class, PsiManager::class)
    }

    override fun tearDown() {
        unmockkStatic(VirtualFileManager::class, PsiManager::class)
        clearAllMocks()
        unmockkAll()
        super.tearDown()
    }

    /**
     * Helper that creates a fake PsiFile with a fake VirtualFile.
     */
    private fun createFakePsiFile(url: String, valid: Boolean = true): PsiFile {
        val vFile = mockk<VirtualFile>(relaxed = true)
        every { vFile.url } returns url
        every { vFile.isValid } returns valid

        val psiFile = mockk<PsiFile>(relaxed = true)
        every { psiFile.virtualFile } returns vFile
        every { psiFile.project } returns project
        every { psiFile.isValid } returns valid

        return psiFile
    }

    /**
     * Helper to stub the chain: when VirtualFileManager.getInstance().findFileByUrl(url)
     * is called it returns the provided virtual file, and PsiManager.getInstance(project).findFile(...)
     * returns the given PsiFile.
     */
    private fun stubGetFile(url: String, psiFile: PsiFile) {
        val vFile = psiFile.virtualFile
        every { VirtualFileManager.getInstance().findFileByUrl(url) } returns vFile
        every { PsiManager.getInstance(project).findFile(vFile) } returns psiFile
    }

    fun testAddRelation() {
        val sourceUrl = "file://source.txt"
        val relatedUrl = "file://related.txt"

        val source = createFakePsiFile(sourceUrl)
        val related = createFakePsiFile(relatedUrl)

        stubGetFile(sourceUrl, source)
        stubGetFile(relatedUrl, related)

        // Subscribe a listener to verify notifications.
        val listener = spyk(object : PsiFileRelationService.RelationChangeListener {
            override fun relationsChanged(changedFile: PsiFile) { /* marker */ }
        })
        project.messageBus.connect(testRootDisposable)
            .subscribe(PsiFileRelationService.RELATION_CHANGE_TOPIC, listener)

        service.addRelation(source, related)

        // Verify that the internal mapping is updated.
        val urlRelations = service.getUrlRelations()
        assertTrue("Source relation should contain related", urlRelations[sourceUrl]?.contains(relatedUrl) ?: false)
        verify { listener.relationsChanged(source) }
    }

    fun testGetRelations() {
        val sourceUrl = "file://source2.txt"
        val relatedUrl1 = "file://related2a.txt"
        val relatedUrl2 = "file://related2b.txt"

        val source = createFakePsiFile(sourceUrl)
        val related1 = createFakePsiFile(relatedUrl1)
        val related2 = createFakePsiFile(relatedUrl2)

        stubGetFile(sourceUrl, source)
        stubGetFile(relatedUrl1, related1)
        stubGetFile(relatedUrl2, related2)

        // Add two relations from the same source.
        service.addRelation(source, related1)
        service.addRelation(source, related2)

        // getRelations runs inside a ReadAction.
        val relations: Map<PsiFile, List<PsiFile>> = service.getRelations(project)
        assertTrue("Relations map should contain the source file", relations.containsKey(source))

        val relatedList = relations[source]
        assertNotNull("Related file list should not be null", relatedList)
        assertTrue("Related list should include related1",
            relatedList!!.any { it.virtualFile.url == relatedUrl1 })
        assertTrue("Related list should include related2",
            relatedList.any { it.virtualFile.url == relatedUrl2 })
    }

    fun testGetRelatedFiles() {
        val sourceUrl = "file://source3.txt"
        val relatedUrl = "file://related3.txt"

        val source = createFakePsiFile(sourceUrl)
        val related = createFakePsiFile(relatedUrl)

        stubGetFile(sourceUrl, source)
        stubGetFile(relatedUrl, related)

        service.addRelation(source, related)

        val relatedFiles = service.getRelatedFiles(source)
        assertTrue("getRelatedFiles should return the related file",
            relatedFiles.any { it.virtualFile.url == relatedUrl })
    }

    fun testRemoveRelation() {
        val sourceUrl = "file://source4.txt"
        val relatedUrl = "file://related4.txt"

        val source = createFakePsiFile(sourceUrl)
        val related = createFakePsiFile(relatedUrl)

        stubGetFile(sourceUrl, source)
        stubGetFile(relatedUrl, related)

        // Add a relation and then remove it.
        service.addRelation(source, related)
        service.removeRelation(source, related)

        val urlRelations = service.getUrlRelations()
        val relatedSet = urlRelations[sourceUrl]
        assertTrue("Relation should be removed",
            relatedSet == null || !relatedSet.contains(relatedUrl))
    }

    fun testStatePersistence() {
        val sourceUrl = "file://source5.txt"
        val relatedUrl = "file://related5.txt"

        val source = createFakePsiFile(sourceUrl)
        val related = createFakePsiFile(relatedUrl)

        stubGetFile(sourceUrl, source)
        stubGetFile(relatedUrl, related)

        service.addRelation(source, related)
        // Retrieve the state as XML.
        val state: Element = service.state

        // Clear the service state.
        service.loadState(Element("PsiFileRelations"))
        assertTrue("State should be empty after clearing", service.getUrlRelations().isEmpty())

        // Reload the previously saved state.
        service.loadState(state)
        val urlRelations = service.getUrlRelations()
        assertTrue("State loaded should restore the relation",
            urlRelations[sourceUrl]?.contains(relatedUrl) ?: false)
    }

    fun testCleanupRelations() {
        val sourceUrl = "file://source6.txt"
        val validUrl = "file://valid.txt"
        val invalidUrl = "file://invalid.txt"

        val source = createFakePsiFile(sourceUrl)
        val relatedValid = createFakePsiFile(validUrl)
        // Create an invalid PsiFile (or at least a not-found virtual file).
        val relatedInvalid = createFakePsiFile(invalidUrl)

        // Stub the source file normally.
        stubGetFile(sourceUrl, source)
        // For the valid URL, stub it so that the file is found.
        stubGetFile(validUrl, relatedValid)
        // For the invalid URL, simulate that no virtual file is found.
        every { VirtualFileManager.getInstance().findFileByUrl(invalidUrl) } returns null
        // (No need to stub PsiManager for the invalid URL.)

        // Add both valid and invalid relations.
        service.addRelation(source, relatedValid)
        service.addRelation(source, relatedInvalid)

        // Execute cleanup.
        service.cleanupRelations()

        val urlRelations = service.getUrlRelations()
        val setAfterCleanup = urlRelations[sourceUrl]
        assertTrue("After cleanup, valid relation should remain", setAfterCleanup?.contains(validUrl) ?: false)
        assertFalse("After cleanup, invalid relation should be removed", setAfterCleanup?.contains(invalidUrl) ?: true)
    }

    fun testGetValidFileReturnsNullOnException() {
        val fakeUrl = "file://exception.txt"
        every { VirtualFileManager.getInstance().findFileByUrl(fakeUrl) } throws RuntimeException("Test Exception")

        // getValidFile should catch the exception and return null.
        val validFile = service.getValidFile(project, fakeUrl)
        assertNull("getValidFile should return null when an exception occurs", validFile)
    }
}

package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class PsiCrawlerTest : BasePlatformTestCase() {

    private val mockProject: Project = MockProject(null) { }

    private fun createFakeService(
        fileMapping: Map<String, PsiFile>,
        urlRelations: Map<String, Set<String>>
    ): PsiFileRelationService {
        return mockk<PsiFileRelationService>(relaxed = true).apply {
            every { getUrlRelations() } returns urlRelations
            every { getValidFile(any(), any()) } answers { fileMapping[arg(1)] }
        }
    }

    fun `test get files with default offset`() {
        val rootFile = myFixture.addFileToProject("root.txt", "root content")
        val child1 = myFixture.addFileToProject("child1.txt", "child1 content")
        val child2 = myFixture.addFileToProject("child2.txt", "child2 content")
        val child3 = myFixture.addFileToProject("child3.txt", "child3 content")
        val child4 = myFixture.addFileToProject("child4.txt", "child4 content")

        val rootUrl = rootFile.virtualFile.url
        val child1Url = child1.virtualFile.url
        val child2Url = child2.virtualFile.url
        val child3Url = child3.virtualFile.url
        val child4Url = child4.virtualFile.url

        val fileMapping = mapOf(
            rootUrl to rootFile,
            child1Url to child1,
            child2Url to child2,
            child3Url to child3,
            child4Url to child4
        )

        val urlRelations = mapOf(
            rootUrl to setOf(child1Url, child2Url, child3Url),
            child1Url to setOf(child4Url)
        )

        val fakeService = createFakeService(fileMapping, urlRelations)
        val psiCrawler = PsiCrawler(mockProject, fakeService)

        val result = psiCrawler.getFiles(rootFile)
        assertEquals(3, result.size)

        val expectedUrls = setOf(rootUrl, child1Url, child2Url)
        val resultUrls = result.map { it.virtualFile.url }.toSet()
        assertEquals(expectedUrls, resultUrls)
    }

    fun `test get files with custom offset`() {
        val rootFile = myFixture.addFileToProject("root_custom.txt", "root custom content")
        val child1 = myFixture.addFileToProject("child1_custom.txt", "child1 custom content")
        val child2 = myFixture.addFileToProject("child2_custom.txt", "child2 custom content")

        val rootUrl = rootFile.virtualFile.url
        val child1Url = child1.virtualFile.url
        val child2Url = child2.virtualFile.url

        val fileMapping = mapOf(
            rootUrl to rootFile,
            child1Url to child1,
            child2Url to child2
        )

        val urlRelations = mapOf(
            rootUrl to setOf(child1Url, child2Url)
        )

        val fakeService = createFakeService(fileMapping, urlRelations)
        val psiCrawler = PsiCrawler(mockProject, fakeService)

        val result = psiCrawler.getFiles(rootFile, offset = 2)
        assertEquals(2, result.size)

        val expectedUrls = setOf(rootUrl, child1Url)
        val resultUrls = result.map { it.virtualFile.url }.toSet()
        assertEquals(expectedUrls, resultUrls)
    }

    fun `test get files with max offset`() {
        val rootFile = myFixture.addFileToProject("root_max.txt", "root max content")
        val child1 = myFixture.addFileToProject("child1_max.txt", "child1 max content")

        val rootUrl = rootFile.virtualFile.url
        val child1Url = child1.virtualFile.url

        val fileMapping = mapOf(
            rootUrl to rootFile,
            child1Url to child1
        )

        val urlRelations = mapOf(
            rootUrl to setOf(child1Url)
        )

        val fakeService = createFakeService(fileMapping, urlRelations)
        val psiCrawler = PsiCrawler(mockProject, fakeService)

        val result = psiCrawler.getFiles(rootFile, offset = 5)
        assertEquals(2, result.size)

        val expectedUrls = setOf(rootUrl, child1Url)
        val resultUrls = result.map { it.virtualFile.url }.toSet()
        assertEquals(expectedUrls, resultUrls)
    }

    // New tests

    fun `test cycle prevention`() {
        val rootFile = myFixture.addFileToProject("root_cycle.txt", "cycle content")
        val childFile = myFixture.addFileToProject("child_cycle.txt", "child content")

        val rootUrl = rootFile.virtualFile.url
        val childUrl = childFile.virtualFile.url

        // Create a cycle: root -> child, and child -> root
        val fileMapping = mapOf(
            rootUrl to rootFile,
            childUrl to childFile
        )
        val urlRelations = mapOf(
            rootUrl to setOf(childUrl),
            childUrl to setOf(rootUrl)
        )

        val fakeService = createFakeService(fileMapping, urlRelations)
        val psiCrawler = PsiCrawler(mockProject, fakeService)

        // With a cycle, even requesting a higher offset should only return the two distinct files.
        val result = psiCrawler.getFiles(rootFile, offset = 3)
        assertEquals(2, result.size)

        val expectedUrls = setOf(rootUrl, childUrl)
        val resultUrls = result.map { it.virtualFile.url }.toSet()
        assertEquals(expectedUrls, resultUrls)
    }

    fun `test caching behavior`() {
        val rootFile = myFixture.addFileToProject("root_cache.txt", "cache content")
        val child1 = myFixture.addFileToProject("child1_cache.txt", "child1 cache content")
        val child2 = myFixture.addFileToProject("child2_cache.txt", "child2 cache content")

        val rootUrl = rootFile.virtualFile.url
        val child1Url = child1.virtualFile.url
        val child2Url = child2.virtualFile.url

        val fileMapping = mapOf(
            rootUrl to rootFile,
            child1Url to child1,
            child2Url to child2
        )
        val urlRelations = mapOf(
            rootUrl to setOf(child1Url, child2Url)
        )

        val fakeService = createFakeService(fileMapping, urlRelations)
        val psiCrawler = PsiCrawler(mockProject, fakeService)

        // First invocation caches the result.
        val result1 = psiCrawler.getFiles(rootFile, offset = 2)
        // Second invocation should retrieve the cached result.
        val result2 = psiCrawler.getFiles(rootFile, offset = 2)
        assertSame("The results should be cached and be the same instance", result1, result2)
    }

    fun `test ignoring missing valid file`() {
        val rootFile = myFixture.addFileToProject("root_invalid.txt", "root content")
        val rootUrl = rootFile.virtualFile.url
        val missingUrl = "file://missing_child.txt"

        // Only the root file exists in the mapping.
        val fileMapping = mapOf(
            rootUrl to rootFile
        )
        // The relation points to a missing file.
        val urlRelations = mapOf(
            rootUrl to setOf(missingUrl)
        )

        val fakeService = createFakeService(fileMapping, urlRelations)
        val psiCrawler = PsiCrawler(mockProject, fakeService)

        // With an invalid child, only the root file is returned.
        val result = psiCrawler.getFiles(rootFile, offset = 3)
        assertEquals(1, result.size)

        val expectedUrls = setOf(rootUrl)
        val resultUrls = result.map { it.virtualFile.url }.toSet()
        assertEquals(expectedUrls, resultUrls)
    }
}

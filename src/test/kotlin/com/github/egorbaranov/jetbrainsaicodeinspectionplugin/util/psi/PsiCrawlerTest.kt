package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor
import com.intellij.util.Query
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import java.util.concurrent.TimeUnit

class PsiCrawlerTest : BasePlatformTestCase() {

    /**
     * Open the given PsiFile in the editor so that FileEditorManager.getInstance(project).openFiles contains it.
     */
    private fun openInEditor(psiFile: PsiFile) {
        FileEditorManager.getInstance(project).openFile(psiFile.virtualFile, false)
    }

    override fun setUp() {
        super.setUp()
        mockkStatic(ReferencesSearch::class)
    }

    fun `test getFilesAsync returns related file via ReferencesSearch`() {
        val fileB = myFixture.addFileToProject(
            "src/p/B.java",
            """
            package p;
            public class B { }
            """.trimIndent()
        )
        val fileA = myFixture.addFileToProject(
            "src/p/A.java",
            """
            package p;
            public class A { }
            """.trimIndent()
        )

        openInEditor(fileA as PsiFile)
        openInEditor(fileB as PsiFile)

        val fakeReference = mockk<PsiReference>(relaxed = true)
        every { fakeReference.resolve() } returns fileB
        every { fakeReference.element } returns fileB  // element.containingFile will be fileB

        every {
            ReferencesSearch.search(any<PsiElement>(), any(), any())
        } returns object : Query<PsiReference> {
            override fun findFirst(): PsiReference? = fakeReference
            override fun findAll(): MutableCollection<PsiReference> = mutableListOf(fakeReference)
            override fun forEach(consumer: Processor<in PsiReference>): Boolean =
                findAll().all { consumer.process(it) }
        }

        val crawler = PsiCrawler.getInstance(project)
        val future = crawler.getFilesAsync(fileA)
        val related = future.get(5, TimeUnit.SECONDS)

        assertEquals(1, related.size)
        assertEquals(fileB.virtualFile.url, related.first().virtualFile.url)
    }

    fun `test getFilesAsync ignores unopened files even when search returns reference`() {
        val fileD = myFixture.addFileToProject(
            "src/r/D.java",
            """
            package r;
            public class D { }
            """.trimIndent()
        )
        val fileC = myFixture.addFileToProject(
            "src/r/C.java",
            """
            package r;
            public class C { }
            """.trimIndent()
        )

        openInEditor(fileC as PsiFile)

        val fakeRef = mockk<PsiReference>(relaxed = true)
        every { fakeRef.resolve() } returns fileD
        every { fakeRef.element } returns fileD

        every {
            ReferencesSearch.search(any<PsiElement>(), any(), any())
        } returns object : Query<PsiReference> {
            override fun findFirst(): PsiReference? = fakeRef
            override fun findAll(): MutableCollection<PsiReference> = mutableListOf(fakeRef)
            override fun forEach(consumer: Processor<in PsiReference>): Boolean =
                findAll().all { consumer.process(it) }
        }

        val crawler = PsiCrawler.getInstance(project)
        val future = crawler.getFilesAsync(fileC)
        val related = future.get(5, TimeUnit.SECONDS)

        assertTrue(related.isEmpty())
    }

    fun `test getFilesAsync with no search results returns empty`() {
        val fileE = myFixture.addFileToProject(
            "src/s/E.java",
            """
            package s;
            public class E { public void foo() { int x = 0; } }
            """.trimIndent()
        )

        openInEditor(fileE as PsiFile)

        every {
            ReferencesSearch.search(any<PsiElement>(), any(), any())
        } returns object : Query<PsiReference> {
            override fun findFirst(): PsiReference? = null
            override fun findAll(): MutableCollection<PsiReference> = mutableListOf()
            override fun forEach(consumer: Processor<in PsiReference>): Boolean = true
        }

        val crawler = PsiCrawler.getInstance(project)
        val future = crawler.getFilesAsync(fileE)
        val related = future.get(5, TimeUnit.SECONDS)

        assertTrue(related.isEmpty())
    }

    fun `test getFilesAsync does not include the original file even if search finds it`() {
        val fileH = myFixture.addFileToProject(
            "src/q/H.java",
            """
            package q;
            public class H { }
            """.trimIndent()
        )

        val fileG = myFixture.addFileToProject(
            "src/q/G.java",
            """
            package q;
            public class G { }
            """.trimIndent()
        )

        openInEditor(fileG as PsiFile)
        openInEditor(fileH as PsiFile)

        val fakeRefG = mockk<PsiReference>(relaxed = true)
        every { fakeRefG.resolve() } returns fileG
        every { fakeRefG.element } returns fileG

        val fakeRefH = mockk<PsiReference>(relaxed = true)
        every { fakeRefH.resolve() } returns fileH
        every { fakeRefH.element } returns fileH

        every {
            ReferencesSearch.search(any<PsiElement>(), any(), any())
        } returns object : Query<PsiReference> {
            override fun findFirst(): PsiReference? = fakeRefG
            override fun findAll(): MutableCollection<PsiReference> = mutableListOf(fakeRefG, fakeRefH)
            override fun forEach(consumer: Processor<in PsiReference>): Boolean =
                findAll().all { consumer.process(it) }
        }

        val crawler = PsiCrawler.getInstance(project)
        val future = crawler.getFilesAsync(fileG)
        val related = future.get(5, TimeUnit.SECONDS)

        assertEquals(1, related.size)
        assertEquals(fileH.virtualFile.url, related.first().virtualFile.url)
    }
}

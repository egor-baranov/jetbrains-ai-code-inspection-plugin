package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.hint

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.BlockInlayPriority
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap

@Suppress("UnstableApiUsage")
class ComplexityInlayHintsCollectorTest : BasePlatformTestCase() {

    private lateinit var inspectionService: InspectionService
    private lateinit var sink: InlayHintsSink

    override fun setUp() {
        super.setUp()
        inspectionService = mockk(relaxed = true)
        sink = mockk(relaxed = true)
    }

    fun `test collect adds hint for class declaration`() {
        myFixture.configureByText("test.kt", "class Sample")
        val virtualFile = myFixture.file.virtualFile!!

        myFixture.editor.caretModel.moveToOffset(
            myFixture.file.text.indexOf("class")
        )
        val psiElement = mockk<PsiElement>()
        val mockNode = mockk<ASTNode>()
        val mockElementType = mockk<IElementType>()
        every { mockElementType.toString() } returns "class"
        every { mockNode.elementType } returns mockElementType
        every { psiElement.node } returns mockNode
        every { psiElement.textOffset } returns 0

        val inspectionMap = ConcurrentHashMap<InspectionService.Inspection, MutableList<InspectionService.CodeFile>>().apply {
            put(
                InspectionService.Inspection("1", "Test", "Fix"),
                mutableListOf(InspectionService.CodeFile(virtualFile.url, "code"))
            )
        }
        every { inspectionService.inspectionFiles } returns inspectionMap

        val collector = ComplexityInlayHintsCollector(myFixture.editor, inspectionService)
        val result = collector.collect(psiElement, myFixture.editor, sink)

        assertTrue("Collector should process valid element", result)

        verify(atLeast = 1) {
            sink.addBlockElement(
                offset = psiElement.textOffset,
                relatesToPrecedingText = false,
                showAbove = true,
                priority = BlockInlayPriority.DOC_RENDER,
                presentation = any()
            )
        }
    }

    fun `test no hint when no matching inspection`() {
        myFixture.configureByText("test.kt", "class Sample")
        val editor = myFixture.editor
        val psiElement = myFixture.file.children[0]

        every { inspectionService.inspectionFiles } returns ConcurrentHashMap()

        val collector = ComplexityInlayHintsCollector(editor, inspectionService)
        val result = collector.collect(psiElement, editor, sink)

        assertFalse(result)
        verify(exactly = 0) { sink.addBlockElement(any(), any(), any(), any(), any()) }
    }
}

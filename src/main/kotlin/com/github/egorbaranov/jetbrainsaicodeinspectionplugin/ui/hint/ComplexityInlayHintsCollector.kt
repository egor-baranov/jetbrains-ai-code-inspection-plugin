package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.hint

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.codeInsight.hints.presentation.RoundWithBackgroundPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.BlockInlayPriority
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.ui.JBColor
import java.awt.Cursor
import java.awt.font.FontRenderContext

@Suppress("UnstableApiUsage")
class ComplexityInlayHintsCollector(
    editor: Editor,
    private val inspectionService: InspectionService
) : FactoryInlayHintsCollector(editor) {

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        val elementType = element.elementType.toString()

        inspectionService
            .inspectionFiles
            .values
            .any { fileList ->
                fileList.any { it.path == editor.virtualFile.url }
            }
            .takeIf { it }
            ?: return false

        if (elementType.contains("object") || elementType.contains("class")) {
            val document = editor.document
            val colorsScheme = editor.colorsScheme
            val font = colorsScheme.getFont(EditorFontType.PLAIN)
            val tabSize = editor.settings.getTabSize(editor.project)

            val marginLeft = ApplicationManager.getApplication().runReadAction<Int> {
                val lineNum = document.getLineNumber(element.textOffset)
                val lineStart = document.getLineStartOffset(lineNum)
                val leadingWhitespace = document.text.substring(lineStart, element.textOffset)

                val spaceWidth = font.getStringBounds(
                    " ",
                    object : FontRenderContext() {}).width
                val tabWidth = spaceWidth * tabSize

                return@runReadAction leadingWhitespace.sumOf { c ->
                    when (c) {
                        ' ' -> spaceWidth
                        '\t' -> tabWidth
                        else -> 0.0
                    }
                }.toInt()
            }

            val text = factory.smallText("Enhance Code")
            val icon = factory.smallScaledIcon(AllIcons.Actions.EnableNewUi)
            val content = factory.seq(icon, factory.inset(text, left = 4))

            val styledPresentation = RoundWithBackgroundPresentation(
                factory.inset(
                    content,
                    left = 6,
                    right = 6,
                    top = 3,
                    down = 3
                ),
                arcWidth = 8,
                arcHeight = 8,
                color = JBColor.namedColor("InlayHints.foreground", JBColor(0x787878, 0x878787)),
                backgroundAlpha = 0.1f
            )

            val marginWrapper = factory.inset(
                styledPresentation,
                left = marginLeft,
                right = 4,
                top = 2,
                down = 2
            )

            sink.addBlockElement(
                element.textOffset,
                relatesToPrecedingText = false,
                showAbove = true,
                priority = BlockInlayPriority.DOC_RENDER,
                presentation = factory.withCursorOnHover(
                    factory.onClick(marginWrapper, MouseButton.Left) { event, _ ->
                        val project = editor.project ?: return@onClick
                        val toolWindowManager = ToolWindowManager.getInstance(project)
                        val toolWindow = toolWindowManager.getToolWindow("AI Code Inspection") ?: return@onClick
                        toolWindow.show()
                    },
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                )
            )
        }
        return true
    }
}
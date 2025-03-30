package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.hint

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi.ElementUsagesUtil
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.codeInsight.hints.presentation.RoundWithBackgroundPresentation
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.BlockInlayPriority
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.util.elementType
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBPanel
import com.intellij.usageView.UsageViewShortNameLocation
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import java.awt.font.FontRenderContext
import javax.swing.JButton
import javax.swing.SwingUtilities

@Suppress("UnstableApiUsage")
class ComplexityInlayHintsCollector(editor: Editor) : FactoryInlayHintsCollector(editor) {

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        val elementType = element.elementType.toString()
        val usages = ElementUsagesUtil.getUsages(element)

        if (usages.isNotEmpty()) {
            println("Usages for element=$element: ${usages.map { it.containingFile.name }.toSet()}")

            for (usage in usages) {
                if (element.containingFile != usage.containingFile) {
                    PsiFileRelationService.getInstance(element.project).addRelation(
                        element.containingFile,
                        usage.containingFile
                    )
                }
            }
        }
        if (elementType.contains("object") || elementType.contains("class")) {
            // Get document and editor settings
            val document = editor.document
            val colorsScheme = editor.colorsScheme
            val font = colorsScheme.getFont(EditorFontType.PLAIN)
            val tabSize = editor.settings.getTabSize(editor.project)

            // Calculate indentation width (thread-safe)
            val marginLeft = ApplicationManager.getApplication().runReadAction<Int> {
                val lineNum = document.getLineNumber(element.textOffset)
                val lineStart = document.getLineStartOffset(lineNum)
                val leadingWhitespace = document.text.substring(lineStart, element.textOffset)

                val spaceWidth =
                    font.getStringBounds(" ", object : FontRenderContext() {}).width // Approximate space width
                val tabWidth = spaceWidth * tabSize

                return@runReadAction leadingWhitespace.sumOf { c ->
                    when (c) {
                        ' ' -> spaceWidth
                        '\t' -> tabWidth
                        else -> 0.0
                    }
                }.toInt()
            }

            // Build presentation (thread-safe)
            val text = factory.smallText(" Fix Complex Code")
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
                        showPopup(event, element)
                    },
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                )
            )
        }
        return true
    }

    private fun showPopup(event: MouseEvent, element: PsiElement) {
        val project = element.project
        val popupPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(400, 600)

            // Create editor with diff
            val originalContent = element.text ?: ""
            val modifiedContent = """
// Optimized ${element.text}
            fun ${element.text}() {
                println("Fixed implementation")
            }
        """.trimIndent()
            val contentFactory = DiffContentFactory.getInstance()
            val language = PsiUtilCore.getLanguageAtOffset(element.containingFile, element.textOffset)
            val fileType = language.associatedFileType ?: PlainTextFileType.INSTANCE
            val contentLeft = contentFactory.create(originalContent, fileType)
            val contentRight = contentFactory.create(modifiedContent, fileType)

            val diffRequest = SimpleDiffRequest("Code Changes", contentLeft, contentRight, "Original", "Modified")
            val diffPanel = DiffManager.getInstance().createRequestPanel(project, project, null)
            diffPanel.setRequest(diffRequest)

            // Button panel
            val buttonPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
                val applyButton = JButton("Apply Fix").apply {
                    putClientProperty("JButton.buttonType", "gradient")
                    putClientProperty("ActionToolbar.ACTION_BUTTON_KEY", true)
                    putClientProperty("ActionButton.smallVariant", true)

                    addActionListener {
                        handleFixAction(element)
                        SwingUtilities.getWindowAncestor(this)?.dispose()
                    }
                }

                val ignoreButton = JButton("Ignore").apply {
                    addActionListener {
                        SwingUtilities.getWindowAncestor(this)?.dispose()
                    }
                }

                add(applyButton)
                add(ignoreButton)
            }

            add(diffPanel.component, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel, popupPanel)
            .setTitle(
                "Fix ${
                    ElementDescriptionUtil.getElementDescription(
                        element,
                        UsageViewShortNameLocation.INSTANCE
                    )
                }"
            )
            .setFocusable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()
            .show(RelativePoint(event))
    }

    private fun handleFixAction(element: PsiElement) {
        // Implement your fix logic here
        println("Applying fix to: ${element.text}")
    }
}
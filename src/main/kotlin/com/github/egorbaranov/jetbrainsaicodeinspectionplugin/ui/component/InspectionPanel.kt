package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.component

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.Metric
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.UIUtils.createRoundedBorder
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.contents.DocumentContentImpl
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.diff.Diff
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class InspectionPanel(
    val project: Project,
    val contentPanel: JPanel,
    val item: InspectionItem
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    val content = JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(4)))

    val codeFilesList = JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(4))).also {
        it.isOpaque = false
    }

    val titleLabel = JBLabel("${item.inspection.description} (${item.affectedFiles.size})").apply {
        font = JBUI.Fonts.label().deriveFont(14f)
        border = JBUI.Borders.emptyLeft(10)
    }

    init {
        isOpaque = false

        border = BorderFactory.createCompoundBorder(
            createRoundedBorder(),
            JBUI.Borders.empty(4)
        )

        val toggleLabel = createToggleButton().also {
            cursor = Cursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(10)
        }

        val deleteLabel = JLabel(AllIcons.Actions.GC).apply {
            cursor = Cursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    MetricService.getInstance(project)
                        .collect(Metric.MetricID.DELETE_INSPECTION)

                    InspectionService.getInstance(project)
                        .removeInspection(item.inspection)

                    contentPanel.remove(this@InspectionPanel)
                    contentPanel.revalidate()
                    contentPanel.repaint()
                }
            })
        }

        titleLabel.text = "${item.inspection.description} (${item.affectedFiles.size})"
        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            isOpaque = false
            add(toggleLabel, BorderLayout.WEST)
            add(titleLabel, BorderLayout.CENTER)
            add(deleteLabel, BorderLayout.EAST)
        }

        content.apply {
            border = JBUI.Borders.empty(4)
            isVisible = false
            isOpaque = false

            val textArea = JTextArea(item.inspection.fixPrompt).apply {
                lineWrap = true
                wrapStyleWord = true
                isEditable = true
                background = JBColor.PanelBackground.brighter()
                border = BorderFactory.createEmptyBorder()

                document.addUndoableEditListener {
                    InspectionService.getInstance(project).setInspectionDescription(
                        item.inspection.id,
                        document.getText(0, document.length)
                    )
                }
            }

            add(Box.createVerticalStrut(8))
            add(textArea)
            add(Box.createVerticalStrut(8))

            add(codeFilesList)
            addFileComponents(item.affectedFiles)
            val buttonPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
                isOpaque = false

                val applyButton = JButton("Apply Fix").apply {
                    isOpaque = false
                    putClientProperty("JButton.buttonType", "gradient")
                    putClientProperty("ActionToolbar.ACTION_BUTTON_KEY", true)
                    putClientProperty("ActionButton.smallVariant", true)

                    addMouseListener(
                        object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent?) {
                                ApplicationManager.getApplication().executeOnPooledThread {
                                    var linesAffected = 0
                                    val filesToUpdate = item.affectedFiles.mapNotNull { codeFile ->
                                        ReadAction.compute<Pair<Document?, String>?, Throwable> {
                                            val document = codeFile.virtualFile()?.findDocument()

                                            if (document != null) {
                                                linesAffected += calculateDiffStats(
                                                    document.text,
                                                    codeFile.content
                                                )
                                            }

                                            codeFile.virtualFile()?.findDocument() to codeFile.content
                                        }
                                    }

                                    MetricService.getInstance(project).collect(
                                        Metric.MetricID.APPLY_FIX,
                                        mapOf(
                                            Metric.MetricParams.FILES_AFFECTED.str to
                                                    item.affectedFiles.size.toString(),
                                            Metric.MetricParams.LINES_APPLIED.str to
                                                    linesAffected.toString(),
                                        )
                                    )

                                    WriteCommandAction.runWriteCommandAction(project) {
                                        filesToUpdate.forEach { (document, content) ->
                                            document?.setText(content)
                                        }
                                        InspectionService.getInstance(project).removeInspection(item.inspection)
                                        contentPanel.remove(this@InspectionPanel)
                                    }
                                }
                            }
                        }
                    )
                }

                val ignoreButton = JButton("Ignore").apply {
                    isOpaque = false
                    addActionListener {
                        MetricService.getInstance(project).collect(Metric.MetricID.IGNORE_FIX)
                        InspectionService.getInstance(project).removeInspection(inspection = item.inspection)
                        contentPanel.remove(this@InspectionPanel)
                    }
                }

                add(applyButton)
                add(ignoreButton)
            }

            add(buttonPanel)
        }

        header.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    content.isVisible = !content.isVisible
                    toggleLabel.icon = getToggleIcon(content.isVisible)
                    revalidate()
                    repaint()
                }
            }
        )

        add(header, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        background = JBColor.PanelBackground.brighter()
        g.color = background
        g.fillRoundRect(0, 0, width, height, 16, 16)
        super.paintComponent(g)
    }

    private fun addFileComponents(codeFiles: List<InspectionService.CodeFile>) {
        codeFiles.forEach { codeFile ->
            val psiFile = ReadAction.compute<PsiFile?, Throwable> {
                codeFile.virtualFile()?.findPsiFile(project)
            }

            if (psiFile != null) {
                codeFilesList.add(
                    createDetailItem(
                        psiFile,
                        item.inspection,
                        codeFile,
                        codeFilesList
                    )
                )
            }

            codeFilesList.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
    }

    fun addFiles(codeFiles: List<InspectionService.CodeFile>) {
        val filtered = codeFiles.filter { codeFile -> !item.affectedFiles.map { it.path }.contains(codeFile.path) }
        item.affectedFiles.addAll(filtered)
        titleLabel.text = "${item.inspection.description} (${item.affectedFiles.size})"
        addFileComponents(filtered)
    }

    private fun createToggleButton(): JLabel {
        return JLabel(getToggleIcon(false))
    }

    private fun getToggleIcon(expanded: Boolean): Icon {
        return if (expanded) AllIcons.Actions.Collapseall else AllIcons.Actions.Expandall
    }

    private fun calculateDiffStats(text1: String, text2: String): Int {
        val lines1 = StringUtil.splitByLines(text1)
        val lines2 = StringUtil.splitByLines(text2)

        val rootChange = Diff.buildChanges(lines1, lines2)

        var deletions = 0
        var insertions = 0

        var currentChange: Diff.Change? = rootChange
        while (currentChange != null) {
            deletions += currentChange.deleted
            insertions += currentChange.inserted
            currentChange = currentChange.link
        }

        return insertions + deletions
    }

    private fun createDetailItem(
        psiFile: PsiFile,
        inspection: InspectionService.Inspection,
        codeFile: InspectionService.CodeFile,
        rootContent: JPanel
    ): JBPanel<*> {
        return object : JBPanel<JBPanel<*>>(BorderLayout()) {
            init {
                val detailItemPanel = this
                isOpaque = true
                background = JBColor.PanelBackground
                border = BorderFactory.createCompoundBorder(
                    createRoundedBorder(),
                    JBUI.Borders.empty(4)
                )

                val originalContent = psiFile.text.orEmpty()
                val modifiedContent = codeFile.content

                val contentFactory = DiffContentFactory.getInstance()
                val language = ReadAction.compute<Language, Throwable> {
                    PsiUtilCore.getLanguageAtOffset(psiFile.containingFile, psiFile.textOffset)
                }
                val fileType = language.associatedFileType ?: PlainTextFileType.INSTANCE
                val contentLeft = contentFactory.create(originalContent, fileType)
                val contentRight = EditorFactory.getInstance().createDocument(modifiedContent)

                val diffRequest = SimpleDiffRequest(
                    "Code Changes",
                    contentLeft,
                    DocumentContentImpl(contentRight),
                    "Original",
                    "Modified"
                )

                var diffPanel: DiffRequestPanel? = null

                ApplicationManager.getApplication().invokeAndWait {
                    diffPanel = DiffManager.getInstance().createRequestPanel(project, project, null)
                }

                ApplicationManager.getApplication().invokeLater {
                    diffPanel?.setRequest(diffRequest)
                }
                diffPanel?.component?.isOpaque = false

                val diffPanelComponent = diffPanel?.component
                diffPanelComponent?.isVisible = false

                val reloadButton = JLabel(AllIcons.Actions.Refresh).apply {
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    toolTipText = "Open ${psiFile.name}"
                    border = JBUI.Borders.empty(4)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            MetricService.getInstance(project).collect(Metric.MetricID.RELOAD)
                            InspectionService.getInstance(project).performFixWithProgress(
                                inspection = inspection,
                                codeFiles = listOf(codeFile)
                            ) {
                                val updatedContent = it.firstOrNull()?.content ?: return@performFixWithProgress
                                ApplicationManager.getApplication().invokeLater {
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        contentRight.setText(updatedContent)
                                    }
                                }
                            }
                        }
                    })
                }

                val openButton = JLabel(AllIcons.Actions.OpenNewTab).apply {
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    toolTipText = "Open ${psiFile.name}"
                    border = JBUI.Borders.empty(4)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            if (psiFile.isValid && !psiFile.project.isDisposed) {
                                MetricService.getInstance(project).collect(Metric.MetricID.OPEN_FILE)
                                FileEditorManager.getInstance(psiFile.project).openFile(
                                    psiFile.virtualFile,
                                    true,
                                    true
                                )
                            }
                        }
                    })
                }

                val deleteButton = JLabel(AllIcons.Actions.GC).apply {
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    toolTipText = "Remove from diff ${psiFile.name}"
                    border = JBUI.Borders.empty(4)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            MetricService.getInstance(project).collect(Metric.MetricID.DELETE_FILE)
                            InspectionService.getInstance(project).removeFileFromInspection(
                                inspection,
                                codeFile
                            )
                            rootContent.remove(detailItemPanel)
                        }
                    })
                }

                val expandButton = JLabel(AllIcons.Actions.Expandall).apply {
                    val button = this
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    icon = getToggleIcon(diffPanelComponent?.isVisible ?: false)
                    toolTipText = "Expand diff for ${psiFile.name}"
                    border = JBUI.Borders.empty(4)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            MetricService.getInstance(project).collect(Metric.MetricID.EXPAND)
                            diffPanelComponent?.isVisible = !diffPanelComponent.isVisible
                            button.icon = getToggleIcon(diffPanelComponent?.isVisible ?: false)
                            revalidate()
                            repaint()
                        }
                    })
                }

                val labelPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                    isOpaque = false
                    add(JBLabel(psiFile.name).apply {
                        border = JBUI.Borders.empty(4)
                        icon = psiFile.fileType.icon
                    }, BorderLayout.CENTER)

                    add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).also {
                        it.add(expandButton)
                        it.add(reloadButton)
                        it.add(openButton)
                        it.add(deleteButton)
                    }, BorderLayout.EAST)
                }

                val centerPanel = object : JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(4))) {
                    init {
                        add(labelPanel)
                        add(diffPanelComponent)
                    }
                }

                add(centerPanel, BorderLayout.CENTER)
            }

            override fun paintComponent(g: Graphics) {
                val g2d = g.create() as Graphics2D
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2d.color = background
                    g2d.fillRoundRect(0, 0, width, height, 16, 16)
                } finally {
                    g2d.dispose()
                }
            }
        }
    }

    data class InspectionItem(
        val inspection: InspectionService.Inspection,
        val affectedFiles: MutableList<InspectionService.CodeFile>
    )
}
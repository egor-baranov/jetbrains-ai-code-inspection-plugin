package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.toolWindow

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.task.RelationsAnalyzerTask
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.component.SkeletonLoadingComponent
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.border.Border

class CodeInspectionToolWindow(
    private val toolWindow: ToolWindow
) {
    private var currentPsiFile: PsiFile? = null
    private val contentPanel = JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(8)
    }
    private val disposer = Disposer.newDisposable()
    private val project = toolWindow.project
    private val rootPanel: JComponent

    init {
        rootPanel = createScrollableContent()
        toolWindow.component.add(rootPanel)
        setupListeners()
        updateContent()  // Initial content load
    }

    fun getContent(): JComponent {
        return rootPanel
    }

    private fun createScrollableContent(): JComponent {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBScrollPane(contentPanel).apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = BorderFactory.createEmptyBorder()
            }, BorderLayout.CENTER)
        }
    }

    private fun setupListeners() {
        // Listen for file selection changes
        project.messageBus.connect(disposer).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    event.newFile?.let { updateCurrentFile(it) }
                }
            })

        // Listen for relation changes
        project.messageBus.connect(disposer).subscribe(
            PsiFileRelationService.RELATION_CHANGE_TOPIC,
            object : PsiFileRelationService.RelationChangeListener {
                override fun relationsChanged(changedFile: PsiFile) {
                    if (changedFile == currentPsiFile) {
                        updateContent()
                    }
                }
            })

        project.messageBus.connect(disposer).subscribe(
            InspectionService.INSPECTION_CHANGE_TOPIC,
            object : InspectionService.InspectionChangeListener {
                override fun inspectionsChanged() {
                    updateContent()
                }

                override fun inspectionLoading(inspection: InspectionService.Inspection) {
                    contentPanel.add(SkeletonLoadingComponent())
                }

                override fun removeInspection(inspection: InspectionService.Inspection) {}

                override fun removeFileFromInspection(
                    inspection: InspectionService.Inspection,
                    codeFile: InspectionService.CodeFile
                ) {
                }
            })
    }

    private fun updateCurrentFile(vFile: VirtualFile) {
        val psiFile = PsiManager.getInstance(project).findFile(vFile)
        if (psiFile != null && psiFile != currentPsiFile) {
            currentPsiFile = psiFile
            updateContent()
        }
    }

    private fun analyzeRelationsWithProgress(project: Project) {
        RelationsAnalyzerTask.execute(
            project = project,
            onProgressUpdate = { status ->
                // Update UI with current status
                println("Analyze progress: $status")
            },
            onComplete = {
                // Handle completion
                println("Analysis completed successfully")
            },
            onError = { error ->
                // Handle errors
                println("Handled error: $error")
            }
        )
    }

    private fun updateContent() {
        // Always check if we're already on EDT
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({ updateContent() }, ModalityState.defaultModalityState())
            return
        }

        // Verify component validity
        if (project.isDisposed || !toolWindow.component.isShowing) return

        // Clear and rebuild content safely
        contentPanel.removeAll()
        println("Update content: ${InspectionService.getInstance(project).inspectionFiles.size}")

        val runIndexingButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.Actions.Execute, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
            it.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        println(
                            "Project file graph: ${
                                PsiFileRelationService.getInstance(project).getRelations(project).map {
                                    "${it.key.name} : ${it.value.size}"
                                }
                            }"
                        )

                        analyzeRelationsWithProgress(project)
                    }
                }
            )
        }

        val stopIndexingButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.Actions.Suspend, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
        }

        val clearAllButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.Actions.GC, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
            it.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        InspectionService.getInstance(project).clearState()
                        updateContent()
                    }
                }
            )
        }

        val settingsButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.General.Settings, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
            it.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        ShowSettingsUtilImpl.showSettingsDialog(
                            project,
                            "ai.code.inspection.settings", // Match the ID from plugin.xml
                            "" // Additional search query (optional)
                        )
                    }
                }
            )
        }

        contentPanel.add(
            JPanel(BorderLayout()).also {
                it.add(
                    JPanel(FlowLayout(FlowLayout.LEFT, 0, 8)).also {
                        it.add(runIndexingButton)
                        it.add(Box.createHorizontalStrut(8))
                        it.add(stopIndexingButton)
                    },
                    BorderLayout.WEST
                )

                it.add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 0, 8)).also {
                        it.add(clearAllButton)
                        it.add(Box.createHorizontalStrut(8))
                        it.add(settingsButton)
                    },
                    BorderLayout.EAST
                )
            }
        )

        try {
            val inspections = InspectionService.getInstance(project).inspectionFiles
            inspections.forEach { (inspection, affectedFiles) ->
                contentPanel.add(
                    createRelationsPanel(
                        inspection = inspection,
                        affectedFiles = affectedFiles
                    )
                )
            }
        } catch (e: Throwable) {
            thisLogger().error("Failed to update content", e)
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createRelationsPanel(
        inspection: InspectionService.Inspection,
        affectedFiles: List<InspectionService.CodeFile>
    ): JComponent {
        return createCollapsiblePanel(
            InspectionItem(
                inspection = inspection,
                affectedFiles = affectedFiles
            )
        ).apply {
            (getComponent(1) as JComponent).components.forEachIndexed { index, component ->
                if (component is JPanel) {
                    component.addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            affectedFiles.getOrNull(index)?.let {
//                                navigateToFile(it)
                            }
                        }
                    })
                }
            }
        }
    }

    private fun createCollapsiblePanel(item: InspectionItem): JBPanel<*> {
        return object : JBPanel<JBPanel<*>>(BorderLayout()) {
            init {
                val collapsiblePanel = this
                isOpaque = false

                // Rounded border
                border = BorderFactory.createCompoundBorder(
                    createRoundedBorder(),
                    JBUI.Borders.empty(4)
                )

                val titleLabel = JBLabel("${item.inspection.description} (${item.affectedFiles.size})").apply {
//                    icon = item.title.fileType.icon
                    font = JBUI.Fonts.label().deriveFont(14f)
                    border = JBUI.Borders.emptyLeft(10)
                }
                val toggleLabel = createToggleButton().also {
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    border = JBUI.Borders.empty(10)
                }

                val deleteLabel = JLabel(AllIcons.Actions.GC).apply {
                    cursor = Cursor(Cursor.HAND_CURSOR)

                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            println("remove inspection: ${item.inspection}")
                            InspectionService.getInstance(project).removeInspection(item.inspection)
                            contentPanel.remove(collapsiblePanel)
                        }
                    })
                }

                val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                    border = JBUI.Borders.empty(4)
                    isOpaque = false
                    add(toggleLabel, BorderLayout.WEST)
                    add(titleLabel, BorderLayout.CENTER)
                    add(deleteLabel, BorderLayout.EAST)
                }

                // Content
                val content = JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(4))).apply {
                    val rootContent = this
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
                            println("document edited")
                            InspectionService.getInstance(project).setInspectionDescription(
                                item.inspection.id,
                                document.getText(0, document.length)
                            )
                        }
                    }

                    add(Box.createVerticalStrut(8))
                    add(textArea)
                    add(Box.createVerticalStrut(8))

                    println("Affected files size: ${item.affectedFiles.size}")

                    item.affectedFiles.forEach { codeFile ->
                        val psiFile = codeFile.virtualFile()?.findPsiFile(project)

                        if (psiFile != null) {
                            add(createDetailItem(psiFile, item.inspection, codeFile, rootContent))
                        }

                        add(Box.createVerticalStrut(JBUI.scale(4)))
                    }

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
                                            val filesToUpdate = item.affectedFiles.mapNotNull { codeFile ->
                                                ReadAction.compute<Pair<Document?, String>?, Throwable> {
                                                    codeFile.virtualFile()?.findDocument() to codeFile.content
                                                }
                                            }

                                            WriteCommandAction.runWriteCommandAction(project) {
                                                filesToUpdate.forEach { (document, content) ->
                                                    document?.setText(content)
                                                }
                                                InspectionService.getInstance(project).removeInspection(item.inspection)
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        val ignoreButton = JButton("Ignore").apply {
                            isOpaque = false
                            addActionListener {
                                InspectionService.getInstance(project).removeInspection(inspection = item.inspection)
                            }
                        }

                        add(applyButton)
                        add(ignoreButton)
                    }

                    add(buttonPanel)
                }

                // Click listener
                header.addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            content.isVisible = !content.isVisible
                            // Access the correct component index
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
        }
    }


    private fun createToggleButton(): JLabel {
        return JLabel(getToggleIcon(false))
    }

    private fun getToggleIcon(expanded: Boolean): Icon {
        return if (expanded) AllIcons.Actions.Collapseall else AllIcons.Actions.Expandall
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
                val language = PsiUtilCore.getLanguageAtOffset(psiFile.containingFile, psiFile.textOffset)
                val fileType = language.associatedFileType ?: PlainTextFileType.INSTANCE
                val contentLeft = contentFactory.create(originalContent, fileType)
                val contentRight = contentFactory.create(modifiedContent, fileType)

                val diffRequest = SimpleDiffRequest("Code Changes", contentLeft, contentRight, "Original", "Modified")
                val diffPanel = DiffManager.getInstance().createRequestPanel(project, project, null)
                diffPanel.setRequest(diffRequest)
                diffPanel.component.isOpaque = false

                val diffPanelComponent = diffPanel.component
                diffPanelComponent.isVisible = false

                // Open file button
                val reloadButton = JLabel(AllIcons.Actions.Refresh).apply {
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    toolTipText = "Open ${psiFile.name}"
                    border = JBUI.Borders.empty(4)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            InspectionService.getInstance(project).performFixWithProgress(
                                inspection = inspection,
                                codeFiles = listOf(codeFile)
                            ) {
                                val updatedContent = it.first().content
                                ApplicationManager.getApplication().invokeLater {

                                    // TODO: fires exception!!
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        contentRight.document.setText(updatedContent)
                                    }
                                }
                            }
                        }
                    })
                }

                // Open file button
                val openButton = JLabel(AllIcons.Actions.OpenNewTab).apply {
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    toolTipText = "Open ${psiFile.name}"
                    border = JBUI.Borders.empty(4)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            if (psiFile.isValid && !psiFile.project.isDisposed) {
                                FileEditorManager.getInstance(psiFile.project).openFile(
                                    psiFile.virtualFile,
                                    true, // request focus
                                    true //
                                )// open in current window
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
                    icon = getToggleIcon(diffPanelComponent.isVisible)
                    toolTipText = "Expand diff for ${psiFile.name}"
                    border = JBUI.Borders.empty(4)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            diffPanelComponent.isVisible = !diffPanelComponent.isVisible
                            button.icon = getToggleIcon(diffPanelComponent.isVisible)
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
                // Layout components
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

    private fun createRoundedBorder(): Border {
        return object : AbstractBorder() {
            private val arc = JBUI.scale(16)
            private val insets = JBUI.insets(4)

            override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
                val g2d = g.create() as Graphics2D
                g2d.color = JBColor.PanelBackground.brighter()
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
                g2d.dispose()
            }

            override fun getBorderInsets(c: Component): Insets = insets
        }
    }

    private data class InspectionItem(
        val inspection: InspectionService.Inspection,
        val affectedFiles: List<InspectionService.CodeFile>
    )
}
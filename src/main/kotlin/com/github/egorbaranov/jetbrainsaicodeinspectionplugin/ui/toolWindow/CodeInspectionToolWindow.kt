package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.toolWindow

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.task.RelationsAnalyzerTask
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.Metric
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.component.SkeletonLoadingComponent
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.UIUtils.createRoundedBorder
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.contents.DocumentContentImpl
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.tasks.TaskManager
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
    private val comboBox = ComboBox(arrayOf(1, 3, 5, 10, 20)).also {
        it.toolTipText = "Inspection offset"
        it.selectedIndex = 1
    }

    init {
        rootPanel = createScrollableContent()
        toolWindow.component.add(rootPanel)
        setupListeners()
        updateContent()
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
        project.messageBus.connect(disposer).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    event.newFile?.let { updateCurrentFile(it) }
                }
            })

        project.messageBus.connect(disposer).subscribe(
            PsiFileRelationService.RELATION_CHANGE_TOPIC,
            object : PsiFileRelationService.RelationChangeListener {
                override fun relationsChanged(changedFile: PsiFile) {
                    if (changedFile == currentPsiFile) {
//                        updateContent()
                    }
                }
            })

        project.messageBus.connect(disposer).subscribe(
            InspectionService.INSPECTION_CHANGE_TOPIC,
            object : InspectionService.InspectionChangeListener {

                override fun inspectionsChanged() {
//                    updateContent()
                }

                override fun inspectionLoading(inspection: InspectionService.Inspection) {
                    println("inspection loading: $inspection")
                    contentPanel.add(SkeletonLoadingComponent())
                }

                override fun inspectionCancelled() {
                    ApplicationManager.getApplication().invokeAndWait {
                        val componentToRemove = contentPanel.components.firstOrNull {
                            it is SkeletonLoadingComponent
                        } ?: return@invokeAndWait
                        contentPanel.remove(componentToRemove)
                    }
                }

                override fun inspectionLoaded(inspection: InspectionService.Inspection) {
                    println("inspection loaded: $inspection")
                    ApplicationManager.getApplication().invokeAndWait {
                        val componentToRemove = contentPanel.components.firstOrNull {
                            it is SkeletonLoadingComponent
                        } ?: return@invokeAndWait

                        contentPanel.remove(componentToRemove)
                        try {
                            val codeFiles = InspectionService.getInstance(project).inspectionFiles[inspection]
                                ?: return@invokeAndWait
                            contentPanel.add(
                                createRelationsPanel(
                                    inspection = inspection,
                                    affectedFiles = codeFiles
                                )
                            )
                        } catch (e: Throwable) {
                            MetricService.getInstance(project).error(e)
                            thisLogger().warn("Failed to update content", e)
                        }

                        contentPanel.revalidate()
                        contentPanel.repaint()
                    }
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

    private fun analyzeRelationsWithProgress(project: Project, inspectionOffset: Int, onComplete: Runnable) {
        ProgressManager.getInstance().run(
            RelationsAnalyzerTask(
                project,
                inspectionOffset = inspectionOffset,
                onProgressUpdate = { status ->
                    println("Analyze progress: $status")
                },
                onComplete = {
                    println("Analysis completed successfully")
                    onComplete.run()
                },
                onError = { error ->
                    println("Handled error: $error")
                }
            )
        )
    }

    private fun updateContent() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({ updateContent() }, ModalityState.defaultModalityState())
            return
        }

        if (project.isDisposed || !toolWindow.component.isShowing) return

        contentPanel.removeAll()
        println("Update content: ${InspectionService.getInstance(project).inspectionFiles.size}")

        val executeAnalysisButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.Actions.Execute, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
            it.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        MetricService.getInstance(project).collect(Metric.MetricID.EXECUTE)
                        analyzeRelationsWithProgress(project, comboBox.item) {
                            println("ANALYZE COMPLETED")
                            updateContent()
                        }
                    }
                }
            )
        }

        val interruptButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.Actions.Suspend, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
            it.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        MetricService.getInstance(project).collect(Metric.MetricID.INTERRUPT)
                        TaskManager.getManager(project).localTasks.forEach { task ->
                            TaskManager.getManager(project).removeTask(task)
                        }
                    }
                }
            )
        }

        val reloadAllButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.Actions.Refresh, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
            it.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        updateContent()
                    }
                }
            )
        }

        val clearAllButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.Actions.GC, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
            it.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        MetricService.getInstance(project).collect(Metric.MetricID.CLEAR_ALL)
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
                        MetricService.getInstance(project).collect(Metric.MetricID.SETTINGS)
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
                        it.add(executeAnalysisButton)
                        it.add(Box.createHorizontalStrut(8))
                        it.add(interruptButton)
                        it.add(Box.createHorizontalStrut(8))
                        it.add(comboBox)
                    },
                    BorderLayout.WEST
                )

                it.add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 0, 8)).also {
                        it.add(reloadAllButton)
                        it.add(Box.createHorizontalStrut(8))
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
            println("All inspections: ${inspections.size}")
            println("Values: ${inspections.map { "id=${it.key.id}, description=${it.key.description}" }}")

            inspections.forEach { (inspection, affectedFiles) ->
                contentPanel.add(
                    createRelationsPanel(
                        inspection = inspection,
                        affectedFiles = affectedFiles
                    )
                )
            }

            println("task size: ${InspectionService.getInstance(project).getTasks()}")
            repeat(InspectionService.getInstance(project).getTasks().size) {
                contentPanel.add(SkeletonLoadingComponent())
            }
        } catch (e: Throwable) {
            MetricService.getInstance(project).error(e)
            thisLogger().warn("Failed to update content", e)
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

                border = BorderFactory.createCompoundBorder(
                    createRoundedBorder(),
                    JBUI.Borders.empty(4)
                )

                val titleLabel = JBLabel("${item.inspection.description} (${item.affectedFiles.size})").apply {
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
                            MetricService.getInstance(project).collect(Metric.MetricID.DELETE_INSPECTION)
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
                        val psiFile = ReadAction.compute<PsiFile?, Throwable> {
                            codeFile.virtualFile()?.findPsiFile(project)
                        }

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

                                            MetricService.getInstance(project).collect(
                                                Metric.MetricID.APPLY_FIX,
                                                mapOf(
                                                    Metric.MetricParams.FILES_AFFECTED.str to
                                                            item.affectedFiles.size.toString(),
                                                    Metric.MetricParams.LINES_APPLIED.str to
                                                            (item.affectedFiles.size * 7).toString(),
                                                )
                                            )

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
                                MetricService.getInstance(project).collect(Metric.MetricID.IGNORE_FIX)
                                InspectionService.getInstance(project).removeInspection(inspection = item.inspection)
                                contentPanel.remove(collapsiblePanel)
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
                            diffPanelComponent?.isVisible = !(diffPanelComponent?.isVisible ?: false)
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

    private data class InspectionItem(
        val inspection: InspectionService.Inspection,
        val affectedFiles: List<InspectionService.CodeFile>
    )
}
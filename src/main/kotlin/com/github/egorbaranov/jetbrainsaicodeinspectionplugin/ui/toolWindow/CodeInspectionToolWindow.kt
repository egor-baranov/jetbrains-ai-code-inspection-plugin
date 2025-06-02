package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.toolWindow

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.task.FileAnalyzerTask
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.Metric
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.component.InspectionPanel
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.component.SkeletonLoadingComponent
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
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

    private val tasks = mutableListOf<Task>()


    init {
        rootPanel = createScrollableContent()
        toolWindow.component.add(rootPanel)
        setupListeners()
        rootPanel.add(buildToolbar(), BorderLayout.NORTH)
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
            InspectionService.INSPECTION_CHANGE_TOPIC,
            object : InspectionService.InspectionChangeListener {

                override fun inspectionLoading(inspectionId: UUID) {
                    contentPanel.add(SkeletonLoadingComponent(inspectionId))
                }

                override fun addFilesToInspection(
                    inspection: InspectionService.Inspection,
                    codeFiles: List<InspectionService.CodeFile>
                ) {
                    ApplicationManager.getApplication().invokeAndWait {
                        val inspectionPanel: InspectionPanel? = contentPanel.components.mapNotNull {
                            it as? InspectionPanel
                        }.firstOrNull {
                            it.item.inspection == inspection
                        }

                        inspectionPanel?.addFiles(codeFiles)
                    }
                }

                override fun inspectionCancelled(inspectionId: UUID) {
                    ApplicationManager.getApplication().invokeAndWait {
                        contentPanel.components.mapNotNull {
                            it as? SkeletonLoadingComponent
                        }.firstOrNull {
                            it.inspectionId == inspectionId
                        }?.let {
                            contentPanel.remove(it)
                        }
                    }
                }

                override fun inspectionLoaded(inspection: InspectionService.Inspection) {
                    ApplicationManager.getApplication().invokeAndWait {
                        contentPanel.components.mapNotNull {
                            it as? SkeletonLoadingComponent
                        }.firstOrNull {
                            it.inspectionId.toString() == inspection.id
                        }?.let {
                            contentPanel.remove(it)
                        }

                        try {
                            val codeFiles = InspectionService.getInstance(project)
                                .inspectionFiles[inspection]
                                .orEmpty()
                                .toMutableList()

                            contentPanel.add(
                                createCollapsiblePanel(
                                    InspectionPanel.InspectionItem(
                                        inspection = inspection,
                                        affectedFiles = codeFiles
                                    )
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

    private fun analyzeFilesWithProgress(project: Project, inspectionOffset: Int, onComplete: Runnable) {
        val task = FileAnalyzerTask(
            project,
            inspectionOffset = inspectionOffset,
            onProgressUpdate = { },
            onComplete = {
                onComplete.run()
            },
            onCancel = { task -> tasks.remove(task) },
            onError = { }
        )

        tasks.add(task)
        ProgressManager.getInstance().run(task)
    }

    private fun cancelAllTasks() {
        synchronized(tasks) {
            tasks.forEach { it.onCancel() }
            tasks.clear()
        }
    }

    private fun buildToolbar(): JPanel {
        val executeAnalysisButton = JButton().also {
            it.icon = IconUtil.resizeSquared(AllIcons.Actions.Execute, 20)
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = Dimension(40, 40)
            it.cursor = Cursor(Cursor.HAND_CURSOR)
            it.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        MetricService.getInstance(project).collect(Metric.MetricID.EXECUTE)
                        analyzeFilesWithProgress(project, comboBox.item) {
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
                        cancelAllTasks()
                        InspectionService.getInstance(project).cancelAllTasks()
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
                            "ai.code.inspection.settings",
                            ""
                        )
                    }
                }
            )
        }

        return JPanel(BorderLayout()).also { panel ->
            panel.add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 8)).also {
                    it.add(executeAnalysisButton)
                    it.add(Box.createHorizontalStrut(8))
                    it.add(interruptButton)
                    it.add(Box.createHorizontalStrut(8))
                    it.add(comboBox)
                },
                BorderLayout.WEST
            )

            panel.add(
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
    }

    private fun updateContent() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({ updateContent() }, ModalityState.defaultModalityState())
            return
        }

        if (project.isDisposed || !toolWindow.component.isShowing) return
        contentPanel.removeAll()

        try {
            val inspections = InspectionService.getInstance(project).inspectionFiles
            inspections.forEach { (inspection, affectedFiles) ->
                contentPanel.add(
                    createCollapsiblePanel(
                        InspectionPanel.InspectionItem(
                            inspection = inspection,
                            affectedFiles = affectedFiles.toMutableList()
                        )
                    )
                )
            }
        } catch (e: Throwable) {
            MetricService.getInstance(project).error(e)
            thisLogger().warn("Failed to update content", e)
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createCollapsiblePanel(item: InspectionPanel.InspectionItem): InspectionPanel = InspectionPanel(
        project,
        contentPanel,
        item
    )
}
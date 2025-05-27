package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.Metric
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.UIUtils
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel
import kotlin.math.sqrt

class PluginConfigurable : SearchableConfigurable {

    private var mySettingsComponent: JPanel? = null

    private lateinit var apiKeyField: JBTextField
    private lateinit var apiUrlField: JBTextField


    override fun getId(): String = "my.plugin.settings"

    @NlsContexts.ConfigurableName
    override fun getDisplayName(): String = "My Plugin Settings"

    private val project: Project? by lazy {
        ProjectManager.getInstance().openProjects.firstOrNull()
    }

    override fun createComponent(): JComponent {
        val settings = PluginSettingsState.getInstance()
        apiKeyField = JBTextField(settings.apiKey, 40).also {
            it.text = PluginSettingsState.getInstance().apiKey
        }
        apiUrlField = JBTextField(settings.apiUrl, 40).also {
            it.text = PluginSettingsState.getInstance().apiUrl
        }

        val metrics = project?.let { MetricService.getInstance(it).getMetrics() }
        val fixesApplied = metrics.orEmpty().count { it.id == Metric.MetricID.APPLY_FIX }
        val filesAffected = metrics.orEmpty().sumOf {
            it.params[Metric.MetricParams.FILES_AFFECTED.str]?.toInt() ?: 0
        }
        val linesAffected = metrics.orEmpty().sumOf {
            it.params[Metric.MetricParams.LINES_APPLIED.str]?.toInt() ?: 0
        }

        val approveRate = metrics?.let {
            it.count {
                it.id == Metric.MetricID.APPLY_FIX
            }.toFloat() * 100 /
                    it.count {
                        it.id == Metric.MetricID.APPLY_FIX ||
                                it.id == Metric.MetricID.IGNORE_FIX ||
                                it.id == Metric.MetricID.DELETE_INSPECTION
                    }
        }?.toInt() ?: 100

        val usabilityScore = metrics?.takeIf { it.isNotEmpty() }?.let {
            approveRate.toFloat() / 100 * (
                    metrics.size - metrics.count {
                        it.id == Metric.MetricID.ERROR
                    } * 3) /
                    metrics.size
        }?.let { sqrt(it) } ?: 1f

        mySettingsComponent = panel {
            this.group("Usage Statistics") {
                row {
                    cell(
                        JPanel().also {
                            it.add(Box.createVerticalStrut(16))
                        }
                    )
                }

                row {
                    this.cell(
                        JPanel(org.jdesktop.swingx.HorizontalLayout()).also {
                            it.add(buildStatPanel("Fixes applied", fixesApplied.toString()))
                            it.add(Box.createHorizontalStrut(16))
                            it.add(buildStatPanel("Files affected", filesAffected.toString()))
                            it.add(Box.createHorizontalStrut(16))
                            it.add(buildStatPanel("Lines affected", linesAffected.toString()))
                            it.add(Box.createHorizontalStrut(16))
                            it.add(buildStatPanel("Approve rate", "$approveRate%"))
                            it.add(Box.createHorizontalStrut(16))
                            it.add(
                                buildStatPanel(
                                    "Usability score",
                                    "%.2f".format(usabilityScore)
                                )
                            )
                        }
                    )
                }

                row {
                    cell(
                        JPanel().also {
                            it.add(Box.createVerticalStrut(4))
                        }
                    )
                }

                row {
                    cell(
                        JScrollPane(
                            JBTable().apply {
                                model = createTableModel()

                                tableHeader.apply {
                                    foreground = JBColor.namedColor("Label.foreground", JBColor.DARK_GRAY)
                                    background = JBColor.namedColor("Panel.background", JBColor.LIGHT_GRAY)
                                    font = font.deriveFont(Font.BOLD)
                                }

                                autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
                                preferredScrollableViewportSize = Dimension(600, 200)

                                addMouseListener(object : MouseAdapter() {
                                    override fun mouseClicked(e: MouseEvent) {
                                        if (e.clickCount == 2) {
                                            val row = rowAtPoint(e.point)
                                            if (row != -1) {
                                                val metricId = model.getValueAt(row, 0) as Metric.MetricID
                                                showMetricDetails(metricId)
                                            }
                                        }
                                    }
                                })

                                setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
                                    override fun getTableCellRendererComponent(
                                        table: JTable,
                                        value: Any?,
                                        isSelected: Boolean,
                                        hasFocus: Boolean,
                                        row: Int,
                                        column: Int
                                    ): Component {
                                        val c = super.getTableCellRendererComponent(
                                            table,
                                            value,
                                            isSelected,
                                            hasFocus,
                                            row,
                                            column
                                        )

                                        c.background = when {
                                            isSelected -> table.selectionBackground
                                            else -> JBColor.WHITE
                                        }

                                        c.foreground = when (column) {
                                            1 -> JBColor.BLUE
                                            else -> JBColor.foreground()
                                        }

                                        return c
                                    }
                                })
                            }
                        ).apply {
                            border = JBUI.Borders.empty(10)
                            preferredSize = Dimension(666, 200)
                            border = UIUtils.createRoundedBorder()
                        }
                    )
                }
            }

            group("Advanced Settings") {

                row {
                    cell(
                        JPanel().also {
                            it.add(Box.createVerticalStrut(4))
                        }
                    )
                }

                row("API key") {
                    cell(apiKeyField)
                }

                row("LLM API url") {
                    cell(apiUrlField)
                }

                row {
                    cell(
                        JPanel().also {
                            it.add(Box.createVerticalStrut(4))
                        }
                    )
                }

                row("Indexing with rich context") {
                    checkBox("")
                }

                row("Retry quantity") {
                    comboBox(listOf(0, 1, 2, 3))
                }

                row {
                    cell(
                        JPanel().also {
                            it.add(Box.createVerticalStrut(8))
                        }
                    )
                }

                buttonsGroup {
                    row {
                        cell(
                            JPanel(GridLayout(0, 3, 10, 5)).apply {
                                border = JBUI.Borders.empty(10)

                                add(JButton("Clear Metrics").apply {
                                    icon = AllIcons.Actions.GC
                                    addActionListener {
                                        project?.let { p -> MetricService.getInstance(p).clearData() }
                                    }
                                    putClientProperty("JButton.buttonType", "gradient")
                                })

                                add(JButton("Export Data").apply {
                                    icon = AllIcons.Actions.Download
                                    addActionListener {
                                        project?.let { p ->
                                            exportStringWithFileChooser(
                                                p,
                                                MetricService.getInstance(p).getMetrics().joinToString("\n") {
                                                    "${it.id} : ${it.params}"
                                                }
                                            )
                                        }
                                    }
                                })

                                add(JButton("Plugin Repository").apply {
                                    icon = AllIcons.General.OpenDiskHover
                                    addActionListener {
                                        BrowserUtil.open("https://github.com/egor-baranov/jetbrains-ai-code-inspection-plugin")
                                    }
                                })
                            }
                        )
                    }
                }
            }
        }
        return mySettingsComponent!!
    }

    private fun showMetricDetails(metricId: Metric.MetricID) {
        val metrics = MetricService.getInstance(project!!).getMetrics().filter { it.id == metricId }
        Messages.showInfoMessage("${metricId.name}: ${metrics.size} entries", "Metric Details")
    }

    private fun createTableModel(): TableModel {
        val metrics = project?.let { MetricService.getInstance(it).getMetrics() } ?: emptyList()
        return object : AbstractTableModel() {
            private val groupedData = metrics.groupBy { it.id }.toList()
            override fun getRowCount() = groupedData.size
            override fun getColumnCount() = 3
            override fun getColumnName(column: Int) = when (column) {
                0 -> "Metric"
                1 -> "Count"
                2 -> "Params"
                else -> ""
            }

            override fun getValueAt(row: Int, column: Int) = when (column) {
                0 -> groupedData[row].first.name
                1 -> groupedData[row].second.size
                2 -> groupedData[row].second.map { it.params }.filter { it.isNotEmpty() }.joinToString { it.toString() }
                else -> null
            }
        }
    }

    private fun exportStringWithFileChooser(project: Project, initialContent: String) {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = FileSaverDescriptor(
                "Export Text Content",
                "Choose location to save text file",
                "txt"
            )

            val fileWrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save("metrics.txt")

            if (fileWrapper != null) {
                try {
                    fileWrapper.virtualFile?.setBinaryContent(
                        initialContent.toByteArray(Charsets.UTF_8)
                    )
                } catch (e: IOException) {
                    Messages.showErrorDialog(
                        project,
                        "Failed to save file: ${e.message}",
                        "Export Failed"
                    )
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = PluginSettingsState.getInstance()
        return apiKeyField.text != settings.apiKey ||
                apiUrlField.text != settings.apiUrl
    }

    override fun apply() {
        val settings = PluginSettingsState.getInstance()
        settings.apiKey = apiKeyField.text
        settings.apiUrl = apiUrlField.text.takeIf { it.isNotEmpty() } ?: PluginSettingsState.DEFAULT_API_URL
    }

    override fun reset() {
        val settings = PluginSettingsState.getInstance()
        apiKeyField.text = settings.apiKey
        apiUrlField.text = settings.apiUrl
    }

    private fun buildStatPanel(titleText: String, mainText: String): JPanel {
        return JPanel(BorderLayout()).also { panel ->
            val titleLabel = JLabel(titleText).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                horizontalAlignment = SwingConstants.CENTER
                border = BorderFactory.createEmptyBorder(8, 0, 4, 0)
            }

            val mainLabel = JLabel(mainText).apply {
                font = font.deriveFont(Font.BOLD, 32f)
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            }

            val centerPanel = JPanel(GridBagLayout()).apply {
                add(mainLabel)
                border = BorderFactory.createEmptyBorder(0, 10, 10, 10)
            }

            panel.add(titleLabel, BorderLayout.NORTH)
            panel.add(centerPanel, BorderLayout.CENTER)

            panel.preferredSize = Dimension(120, 100)
            panel.minimumSize = Dimension(120, 100)
            panel.border = UIUtils.createRoundedBorder()
            panel.background = JBColor.namedColor("Panel.background", JBColor.WHITE)
        }
    }
}
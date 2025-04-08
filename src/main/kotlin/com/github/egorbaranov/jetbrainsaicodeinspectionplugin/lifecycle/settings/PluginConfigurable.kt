package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.Metric
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.component.ChartPanel
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.UIUtils
import com.intellij.icons.AllIcons
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
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

class PluginConfigurable : SearchableConfigurable {

    private var mySettingsComponent: JPanel? = null
    private var textField: JBTextField? = null

    // Unique ID for the configurable (used when opening programmatically)
    override fun getId(): String = "my.plugin.settings"

    // Display name in the settings dialog
    @NlsContexts.ConfigurableName
    override fun getDisplayName(): String = "My Plugin Settings"

    private val project: Project? by lazy {
        // Get project from current context
        ProjectManager.getInstance().openProjects.firstOrNull()
    }

    // Create the settings UI
    override fun createComponent(): JComponent {
        val settings = PluginSettingsState.getInstance()
        textField = JBTextField(settings.someSetting, 20)

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
                            it.add(buildStatPanel("Fixes applied", "42"))
                            it.add(Box.createHorizontalStrut(16))
                            it.add(buildStatPanel("Files affected","168"))
                            it.add(Box.createHorizontalStrut(16))
                            it.add(buildStatPanel("Lines affected", "496"))
                            it.add(Box.createHorizontalStrut(16))
                            it.add(buildStatPanel("Approve rate", "68%"))
                            it.add(Box.createHorizontalStrut(16))
                            it.add(buildStatPanel("Usability score", "0.34"))
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

                                // 1. Header styling
                                tableHeader.apply {
                                    foreground = JBColor.namedColor("Label.foreground", Color.DARK_GRAY)
                                    background = JBColor.namedColor("Panel.background", Color.LIGHT_GRAY)
                                    font = font.deriveFont(Font.BOLD)
                                }

                                // 2. Full width configuration
                                autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
                                preferredScrollableViewportSize = Dimension(600, 200)

                                // 3. Double-click listener
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

                                // 4. Cell styling
                                setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
                                    override fun getTableCellRendererComponent(
                                        table: JTable,
                                        value: Any?,
                                        isSelected: Boolean,
                                        hasFocus: Boolean,
                                        row: Int,
                                        column: Int
                                    ): Component {
                                        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

                                        // Background colors
                                        c.background = when {
                                            isSelected -> table.selectionBackground
                                            else -> JBColor.WHITE
                                        }

                                        // Text colors
                                        c.foreground = when (column) {
                                            1 -> JBColor.BLUE
                                            else -> JBColor.foreground()
                                        }

                                        return c
                                    }
                                })
                            }
                        ).apply {
                            // 5. Full width scroll pane
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
                    textField()
                }

                row {
                    cell(
                        JPanel().also {
                            it.add(Box.createVerticalStrut(4))
                        }
                    )
                }

                row("LLM API URL") {
                    textField()
                }

                row {
                    cell(
                        JPanel().also {
                            it.add(Box.createVerticalStrut(16))
                        }
                    )
                }

                buttonsGroup {
                    row {
                        cell(
                            JPanel(GridLayout(0, 3, 10, 5)).apply {
                                border = JBUI.Borders.empty(10)

                                // Regular text button
                                add(JButton("Clear Metrics").apply {
                                    icon = AllIcons.Actions.GC
                                    addActionListener {
                                    }
                                    putClientProperty("JButton.buttonType", "gradient")
                                })

                                // Button with icon
                                add(JButton("Export Data").apply {
                                    icon = AllIcons.Actions.Download
                                    addActionListener {
                                    }
                                })

                                // Toggle buttons
                                add(JButton("Plugin Repository").apply {
                                    icon = AllIcons.General.OpenDiskHover
                                    addActionListener {
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
        // Your implementation to show details
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

    private class BoldHeaderRenderer : DefaultTableCellRenderer() {
        init {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(4)
        }
    }

    private class RightAlignRenderer : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = RIGHT
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).apply {
                text = value?.toString() ?: ""
            }
        }
    }



    // Check if settings were modified
    override fun isModified(): Boolean {
        val settings = PluginSettingsState.getInstance()
        return textField?.text != settings.someSetting
    }

    // Apply changes (save)
    override fun apply() {
        val settings = PluginSettingsState.getInstance()
        settings.someSetting = textField?.text ?: ""
    }

    // Reset to saved state
    override fun reset() {
        val settings = PluginSettingsState.getInstance()
        textField?.text = settings.someSetting
    }

    private fun buildStatPanel(titleText: String, mainText: String): JPanel {
        return JPanel(BorderLayout()).also { panel ->
            // Title label
            val titleLabel = JLabel(titleText).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = JBColor.namedColor("Label.infoForeground", Color.GRAY)
                horizontalAlignment = SwingConstants.CENTER
                border = BorderFactory.createEmptyBorder(8, 0, 4, 0)
            }

            // Main text label
            val mainLabel = JLabel(mainText).apply {
                font = font.deriveFont(Font.BOLD, 32f)
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            }

            // Wrapper for centering in BorderLayout
            val centerPanel = JPanel(GridBagLayout()).apply {
                add(mainLabel)
                border = BorderFactory.createEmptyBorder(0, 10, 10, 10)
            }

            // Add components to main panel
            panel.add(titleLabel, BorderLayout.NORTH)
            panel.add(centerPanel, BorderLayout.CENTER)

            // Styling
            panel.preferredSize = Dimension(120, 100)
            panel.minimumSize = Dimension(120, 100)
            panel.border = UIUtils.createRoundedBorder()
            panel.background = JBColor.namedColor("Panel.background", Color.WHITE)
        }
    }

    private fun createStatPanel(title: String, chart: ChartPanel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT

            // Title label
            add(JLabel(title).apply {
                font = font.deriveFont(Font.BOLD)
                border = BorderFactory.createEmptyBorder(0, 0, 5, 0)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })

            // Chart
            add(chart.apply {
                preferredSize = Dimension(300, 250)
                minimumSize = Dimension(300, 250)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }
    }

}
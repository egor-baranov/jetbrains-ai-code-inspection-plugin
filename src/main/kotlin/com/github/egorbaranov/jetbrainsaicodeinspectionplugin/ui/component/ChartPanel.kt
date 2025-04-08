package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.component

import java.awt.*
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.JPanel

class ChartPanel : JPanel() {
    private val dateFormat = SimpleDateFormat("dd MMM")
    private val stroke = BasicStroke(2f)
    private val pointSize = 6

    private var metrics = listOf<Metric>()

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        if (metrics.isEmpty()) metrics = getMetrics()

        val width = width
        val height = height
        val padding = 50
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding

//        border = UIUtils.createRoundedBorder()

        // Draw background
        g2d.color = Color.WHITE
        g2d.fillRect(padding, padding, chartWidth, chartHeight)

        // Calculate bounds
        val maxValue = metrics.maxOf { it.value }.toFloat()
        val minDate = metrics.minOf { it.timestamp.time }
        val maxDate = metrics.maxOf { it.timestamp.time }
        val dateRange = maxDate - minDate

        // Draw axes
        g2d.color = Color.white
        g2d.drawLine(padding, height - padding, width - padding, height - padding) // X-axis
        g2d.drawLine(padding, padding, padding, height - padding) // Y-axis

        // Draw labels
        g2d.font = g2d.font.deriveFont(8f)

        // Y-axis labels
        val yStep = chartHeight / 5
        for (i in 0..5) {
            val y = height - padding - (i * yStep)
            val value = (maxValue * i / 5).toInt()
            g2d.drawString(value.toString(), padding - 40, y + 5)
            g2d.color = Color.white
        }

        // X-axis labels
        val xStep = chartWidth / 5
        for (i in 0..5) {
            val x = padding + (i * xStep)
            val date = Date(minDate + (dateRange * i / 5))
            g2d.drawString(dateFormat.format(date), x - 20, height - padding + 30)
        }

        // Draw data points and lines
        g2d.color = Color(0, 100, 200)
        g2d.stroke = stroke

        val points = metrics.map { metric ->
            val x = padding + ((metric.timestamp.time - minDate).toFloat() / dateRange * chartWidth).toInt()
            val y = height - padding - ((metric.value.toFloat() / maxValue) * chartHeight).toInt()
            Pair(x, y)
        }

        // Draw lines
        for (i in 0 until points.size - 1) {
            g2d.drawLine(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
        }

        // Draw points
        points.forEach { (x, y) ->
            g2d.fillOval(x - pointSize / 2, y - pointSize / 2, pointSize, pointSize)
        }
    }

    fun getMetrics(): List<Metric> {
        return metrics.ifEmpty {
            // Generate sample data if empty
            listOf(
                Metric(Date(System.currentTimeMillis() - 86400000 * 7), 12),
                Metric(Date(System.currentTimeMillis() - 86400000 * 6), 15),
                Metric(Date(System.currentTimeMillis() - 86400000 * 5), 18),
                Metric(Date(System.currentTimeMillis() - 86400000 * 4), 24),
                Metric(Date(System.currentTimeMillis() - 86400000 * 3), 20),
                Metric(Date(System.currentTimeMillis() - 86400000 * 2), 28),
                Metric(Date(), 32)
            ).also { metrics = it }
        }
    }

    data class Metric(
        val timestamp: Date = Date(),
        val value: Int
    )
}

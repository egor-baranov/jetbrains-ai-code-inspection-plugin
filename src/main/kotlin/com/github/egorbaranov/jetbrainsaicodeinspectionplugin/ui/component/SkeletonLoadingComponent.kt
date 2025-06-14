package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.component

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.util.*
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.sin

class SkeletonLoadingComponent(val inspectionId: UUID) : JPanel() {

    private val animationTimer: Timer
    private var alpha = 0f

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.namedColor("Panel.background", JBColor.WHITE)
        border = JBUI.Borders.emptyBottom(8)

        preferredSize = Dimension(
            WIDTH,
            JBUI.scale(44)
        )

        isOpaque = false
        animationTimer = Timer(5) {
            alpha = (sin(System.currentTimeMillis() / 400.0) * 0.3 + 0.7).toFloat()
            repaint()
        }

        animationTimer.start()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val arc = JBUI.scale(16)
        g2.color = JBColor(Color(0xEBEBEB), Color(0x4B4B4B))
        g2.fillRoundRect(0, 0, width, height, arc, arc)

        val overlayAlpha = (alpha * 0.3f).coerceIn(0f, 1f)
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, overlayAlpha)

        val gradient = GradientPaint(
            0f, 0f,
            JBColor(
                Color(0, 0, 0, 0),
                Color(0, 0, 0, 0)
            ),
            width * 0.4f,
            0f,
            JBColor(
                Color(255, 255, 255, 150),
                Color(255, 255, 255, 40)
            ),
            true
        )

        g2.paint = gradient
        g2.fillRoundRect(0, 0, width, height, arc, arc)
    }

    override fun removeNotify() {
        super.removeNotify()
        stopAnimation()
    }

    fun stopAnimation() {
        animationTimer.stop()
    }

    companion object {
        private val WIDTH = JBUI.scale(200)
    }
}

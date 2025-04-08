package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.border.AbstractBorder
import javax.swing.border.Border

object UIUtils {
    fun createRoundedBorder(): Border {
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
}
package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.marker

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import java.awt.event.MouseEvent
import java.util.function.Supplier
import javax.swing.JPanel

class ComplexityInfoHint(anchor: PsiElement, textRange: TextRange): LineMarkerInfo<PsiElement>(
    anchor,
    textRange,
    AllIcons.Actions.EnableNewUi,
    { _ -> "Complexity Info Hint" },
    getCommonNavigationHandler(),
    GutterIconRenderer.Alignment.LEFT,
    Supplier<String> { "" }
) {
    companion object {

        private fun getCommonNavigationHandler(): GutterIconNavigationHandler<PsiElement> {
            return GutterIconNavigationHandler<PsiElement> { mouseEvent: MouseEvent?, _: PsiElement? ->
                val panel = JPanel()
                panel.add(JBLabel("ComplexityInfoHint example"))
                JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
                    .createPopup()
                    .show(RelativePoint(mouseEvent!!))
            }
        }
    }
}
package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import javax.swing.Icon

class ComplexityLineMarkerProvider: LineMarkerProviderDescriptor() {

    override fun getName(): String = "ComplexityAnalyzer"

    override fun getIcon(): Icon = AllIcons.Actions.EnableNewUi
    override fun getLineMarkerInfo(p0: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        println("collectSlowLineMarkers")

        for (element in elements) {
            val hint = ComplexityInfoHint(element, TextRange.EMPTY_RANGE)
            result.add(hint)
        }
    }
}
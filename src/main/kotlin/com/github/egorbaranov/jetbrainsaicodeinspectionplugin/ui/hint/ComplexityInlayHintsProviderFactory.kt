package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.hint

import com.intellij.codeInsight.hints.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.ProviderInfo
import com.intellij.lang.Language

@Suppress("UnstableApiUsage")
class ComplexityInlayHintsProviderFactory: InlayHintsProviderFactory {
    override fun getProvidersInfo(): List<ProviderInfo<out Any>> {
        return listOf(ProviderInfo(Language.findLanguageByID("kotlin")!!, ComplexityInlayHintsProvider()))
    }
}
package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.inspections

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.CodeContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor

class CodeContextCollector(private val element: PsiElement) {
    fun collect(): CodeContext {
        return CodeContext(
            language = element.language.displayName,
            codeSnippet = element.text,
            structure = "",
            complexityFactors = listOf()
        )
    }
}
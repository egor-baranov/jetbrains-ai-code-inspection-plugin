package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.inspections

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.quickfixes.ComplexityQuickFix
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

class ComplexityInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            println("visit element: $element")
            if (isComplexCodeBlock(element)) {
                registerProblem(element, holder)
            }
        }
    }

    private fun isComplexCodeBlock(element: PsiElement): Boolean {
        return when {
            isFunction(element) -> true
            isCodeBlock(element) -> true
            else -> true
        }
    }

    private fun registerProblem(element: PsiElement, holder: ProblemsHolder) {
        holder.registerProblem(
            element,
            "Complex code block detected",
            ProblemHighlightType.WARNING,
            ComplexityQuickFix(element)
        )
    }

    private fun isFunction(element: PsiElement): Boolean {
        return element is PsiNamedElement && PsiTreeUtil.findChildrenOfType(
            element,
            PsiCodeFragment::class.java
        ).isNotEmpty()
    }

    private fun isCodeBlock(element: PsiElement): Boolean {
        return element is PsiCodeFragment
    }
}
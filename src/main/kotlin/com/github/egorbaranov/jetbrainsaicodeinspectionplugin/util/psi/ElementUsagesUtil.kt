package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch


object ElementUsagesUtil {

    // TODO: why isnt it used?
    fun getUsages(element: PsiElement): List<PsiElement> {
        return ReadAction.compute<List<PsiElement>, Throwable> {
            return@compute ReferencesSearch.search(
                element,
                GlobalSearchScope.allScope(element.project),
                false
            ).findAll().map { it.element }
        }
    }
}
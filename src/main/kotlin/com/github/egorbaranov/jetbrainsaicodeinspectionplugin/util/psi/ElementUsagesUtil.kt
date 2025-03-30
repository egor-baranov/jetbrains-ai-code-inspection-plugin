package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch


object ElementUsagesUtil {

    fun getUsages(element: PsiElement): List<PsiElement> {
        return ReadAction.compute<List<PsiElement>, Throwable> {
            val project = element.project
            // Define the search scope (entire project)
            val scope = GlobalSearchScope.allScope(project)
            // Search for references to the element, excluding its declaration
            val references = ReferencesSearch.search(element, scope, false).findAll()
            // Extract the elements containing the references
            references.map { it.element }
        }
    }
}
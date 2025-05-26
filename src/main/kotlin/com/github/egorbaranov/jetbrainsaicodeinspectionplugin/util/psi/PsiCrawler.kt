package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch

@Service(Service.Level.PROJECT)
class PsiCrawler(
    private val project: Project
) {

    fun getFiles(file: PsiFile, offset: Int = 3): List<PsiFile> {
        val project: Project = file.project
        val relatedFiles = mutableSetOf<PsiFile>()
        val fileIndex = ProjectFileIndex.getInstance(project)

        file.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)

                element.references.forEach { ref ->
                    val resolved = ref.resolve()
                    resolved?.containingFile?.let { resolvedFile ->
                        if (isInProject(resolvedFile.virtualFile, fileIndex)) {
                            relatedFiles.add(resolvedFile)
                        }
                    }
                }

                if (element is PsiNamedElement) {
                    ReferencesSearch.search(
                        element,
                        GlobalSearchScope.projectScope(project),
                        false
                    ).findAll().forEach { ref ->
                        ref.element.containingFile?.let { usageFile ->
                            if (isInProject(usageFile.virtualFile, fileIndex)) {
                                relatedFiles.add(usageFile)
                            }
                        }
                    }
                }
            }
        })

        relatedFiles.remove(file)
        return relatedFiles.toList()
    }

    private fun isInProject(virtualFile: VirtualFile?, fileIndex: ProjectFileIndex): Boolean {
        return virtualFile != null && fileIndex.isInContent(virtualFile)
    }

    companion object {

        fun getInstance(project: Project): PsiCrawler = project.service()
    }
}
package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.startup

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.ProjectIndexer
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi.ElementUsagesUtil
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType

class CodeInspectionProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        println("Project startup")

        val indexer = ProjectIndexer(project)
        val indexingHandler = object : ProjectIndexer.IndexingHandler {
            override fun shouldProcess(element: PsiElement): Boolean {
                val elementType = element.elementType.toString()
                println("Should process element $elementType")
                return listOf("class", "object").any {
                    elementType.lowercase().contains(it)
                }
            }

            override fun processElement(element: PsiElement) {
                println("Process element: $element")
                val usages = ElementUsagesUtil.getUsages(element)
                if (usages.isNotEmpty()) {
                    println("Usages for element=$element: ${usages.map { it.containingFile.name }.toSet()}")

                    for (usage in usages) {
                        if (element.containingFile != usage.containingFile) {
                            PsiFileRelationService.getInstance(element.project).addRelation(
                                element.containingFile,
                                usage.containingFile
                            )
                        }
                    }
                }
            }

            override fun onComplete(index: Map<String, PsiElement>) {
                println("onComplete: $index")
            }

            override fun onError(error: Throwable) {
                println("onError: ${error.stackTraceToString()}")
            }
        }

        indexer.startIndexing(handler = indexingHandler)
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }
}
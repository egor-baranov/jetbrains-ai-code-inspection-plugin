package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.startup

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.ProjectIndexer
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService.Companion.logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.elementType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

            override suspend fun processElement(element: PsiElement) {
                try {
                    // Process usages in background thread but wrap PSI access in read actions
                    val usages = withContext(Dispatchers.Default) {
                        ApplicationManager.getApplication().runReadAction<Array<PsiElement>> {
                            ReferencesSearch.search(
                                element,
                                GlobalSearchScope.allScope(element.project),
                                false
                            ).findAll().map { it.element }.toTypedArray()
                        }
                    }

                    // Process results on EDT with read access
                    withContext(Dispatchers.EDT) {
                        ApplicationManager.getApplication().runReadAction {
                            usages.toList().forEach { usage ->
                                val containingFile = usage.containingFile

                                if (element.containingFile != containingFile) {
                                    PsiFileRelationService.getInstance(project).addRelation(
                                        element.containingFile,
                                        containingFile
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error processing usages", e)
                }
            }


            override fun onComplete(index: Map<String, PsiElement>) {
                println("onComplete: $index")
            }

            override fun onError(error: Throwable) {
                println("onError: ${error.stackTraceToString()}")
            }
        }

        DumbService.getInstance(project).runWhenSmart {
            indexer.startIndexing(handler = indexingHandler)
            thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
        }
    }
}
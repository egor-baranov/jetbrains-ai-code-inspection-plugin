package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

class PsiCrawler(
    private val project: Project,
    private var psiFileRelationService: PsiFileRelationService = PsiFileRelationService.getInstance(project)
) {

    private val cache = ConcurrentHashMap<Pair<String, Int>, List<PsiFile>>()

    fun getFiles(rootFile: PsiFile, offset: Int = 3): List<PsiFile> {
        val project = rootFile.project
        return ReadAction.nonBlocking<List<PsiFile>> {
            val rootUrl = getFileUrl(rootFile) ?: return@nonBlocking emptyList()
            val cacheKey = rootUrl to offset

            cache[cacheKey]?.let { return@nonBlocking it }

            val result = mutableListOf<PsiFile>()
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()

            queue.add(rootUrl)
            visited.add(rootUrl)

            while (queue.isNotEmpty() && result.size < offset) {
                val currentUrl = queue.removeFirst()
                getValidFile(project, currentUrl)?.let { psiFile ->
                    if (result.size < offset) {
                        result.add(psiFile)
                    }
                }

                if (result.size < offset) {
                    val childUrls = getChildUrls(currentUrl)
                    childUrls.filter { visited.add(it) }
                        .forEach {
                            println("queue add $it")
                            queue.add(it)
                        }
                }
            }

            result.take(offset).toSet().toList().also { cache[cacheKey] = it }
        }.executeSynchronously()
    }

    private fun getFileUrl(file: PsiFile): String? {
        return file.virtualFile?.url
    }

    private fun getChildUrls(url: String): Set<String> {
        return psiFileRelationService
            .getUrlRelations()
            .getOrDefault(url, emptySet())
    }

    private fun getValidFile(project: Project, url: String): PsiFile? {
        return psiFileRelationService.getValidFile(project, url)
    }
}
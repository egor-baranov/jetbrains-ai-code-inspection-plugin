package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.context.PsiFileRelationService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.util.concurrent.ConcurrentHashMap

class PsiCrawler(private val project: Project) {

    private val cache = ConcurrentHashMap<Pair<String, Int>, List<PsiFile>>()

    @RequiresEdt
    fun getFiles(rootFile: PsiFile, offset: Int = 3): List<PsiFile> {
        return ReadAction.compute<List<PsiFile>, Throwable> {
            val rootUrl = getFileUrl(rootFile) ?: return@compute emptyList()
            val cacheKey = rootUrl to offset

            cache[cacheKey]?.let { return@compute it }

            val result = mutableListOf<PsiFile>()
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()

            queue.add(rootUrl)
            visited.add(rootUrl)

            while (queue.isNotEmpty() && result.size < offset) {
                val currentUrl = queue.removeFirst()
                getValidFile(currentUrl)?.let { psiFile ->
                    if (result.size < offset) {
                        result.add(psiFile)
                    }
                }

                if (result.size < offset) {
                    getChildUrls(currentUrl)
                        .filter { visited.add(it) }
                        .forEach { queue.add(it) }
                }
            }

            result.take(offset).toList().also { cache[cacheKey] = it }
        }
    }

    private fun getFileUrl(file: PsiFile): String? {
        return file.virtualFile?.path
    }

    private fun getChildUrls(url: String): Set<String> {
        return PsiFileRelationService.getInstance(project)
            .getUrlRelations()
            .getOrDefault(url, emptySet())
    }

    private fun getValidFile(url: String): PsiFile? {
        return PsiFileRelationService.getInstance(project)
            .getValidFile(project, url)
    }
}
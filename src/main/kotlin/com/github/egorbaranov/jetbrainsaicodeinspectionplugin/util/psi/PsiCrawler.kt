package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings.PluginSettingsState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class PsiCrawler(
    private val project: Project
) {
    /**
     * Asynchronously computes the set of related PsiFiles.
     * Returns a CompletableFuture by adapting IntelliJ's CancellablePromise.
     */
    fun getFilesAsync(file: PsiFile, offset: Int = 3): CompletableFuture<List<PsiFile>> {
        // non-blocking read action returning a CancellablePromise
        val promise: CancellablePromise<List<PsiFile>> = ReadAction.nonBlocking<List<PsiFile>> {
            val related = mutableSetOf<PsiFile>()
            val fileIndex = ProjectFileIndex.getInstance(project)

            file.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    super.visitElement(element)

                    element.references.forEach { ref ->
                        ref.resolve()?.containingFile?.let { f ->
                            if (isInProject(f.virtualFile, fileIndex)) {
                                related.add(f)
                            }
                        }
                    }

                    if (element is PsiNamedElement) {
                        val scope = if (PluginSettingsState.getInstance().richIndexingContext) {
                            GlobalSearchScope.allScope(project)
                        } else {
                            GlobalSearchScope.filesScope(
                                project,
                                FileEditorManager.getInstance(project).openFiles.toList()
                            )
                        }

                        ReferencesSearch.search(element, scope, false)
                            .findAll()
                            .mapNotNull { it.element.containingFile }
                            .filter { isInProject(it.virtualFile, fileIndex) }
                            .forEach { related.add(it) }
                    }
                }
            })

            related.remove(file)
            related.toList()
        }
            .coalesceBy(this, file)
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())

        return promiseToFuture(promise)
    }

    /**
     * Converts a CancellablePromise to a CompletableFuture, forwarding cancellation.
     */
    private fun <T> promiseToFuture(promise: CancellablePromise<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        promise.onSuccess { result -> future.complete(result) }
        promise.onError { error -> future.completeExceptionally(error) }
        future.whenComplete { _, exception ->
            if (future.isCancelled) {
                promise.cancel()
            }
        }
        return future
    }

    private fun isInProject(vf: VirtualFile?, fileIndex: ProjectFileIndex): Boolean =
        vf != null
                && fileIndex.isInContent(vf)
                && (PluginSettingsState.getInstance().richIndexingContext
                || FileEditorManager.getInstance(project).openFiles.contains(vf))

    companion object {
        fun getInstance(project: Project): PsiCrawler = project.service()
    }
}

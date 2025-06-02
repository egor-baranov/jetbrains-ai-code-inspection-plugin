package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util.psi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
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
): Disposable {

    private val disposable: Disposable = Disposer.newDisposable("PsiCrawlerDisposable").also {
        Disposer.register(this, it)
    }

    /**
     * Asynchronously computes the set of related PsiFiles.
     * Returns a CompletableFuture by adapting IntelliJ's CancellablePromise.
     */
    fun getFilesAsync(file: PsiFile): CompletableFuture<List<PsiFile>> {
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
                        val scope = GlobalSearchScope.allScope(project)
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
            .expireWith(disposable)
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
                && FileEditorManager.getInstance(project).openFiles.contains(vf)

    override fun dispose() {
        Disposer.dispose(disposable)
    }

    companion object {
        fun getInstance(project: Project): PsiCrawler = project.service()
    }
}

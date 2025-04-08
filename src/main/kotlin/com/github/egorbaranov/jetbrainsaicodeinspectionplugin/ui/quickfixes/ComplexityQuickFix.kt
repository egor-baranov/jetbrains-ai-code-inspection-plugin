package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.quickfixes

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics.MetricService
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import java.awt.BorderLayout
import javax.swing.*

class ComplexityQuickFix(private val element: PsiElement) : LocalQuickFix {

    override fun getFamilyName(): String = "AI code refactor"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val dialog = createProgressDialog(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                processRefactoring(project, dialog)
            } catch (e: Exception) {
                MetricService.getInstance(project).error(e)
                handleError(dialog, e)
            }
        }
        dialog.show()
    }

    private fun createProgressDialog(project: Project): RefactorDialog {
        return RefactorDialog(project).apply {
            setTitle("AI Code Refactoring")
            pack()
        }
    }

    private fun processRefactoring(project: Project, dialog: RefactorDialog) {
        dialog.updateProgress("Analyzing code structure...", 20)

        dialog.updateProgress("Generating suggestions...", 40)
    }

    private fun showSuggestions(suggestions: String) {
        val panel = JPanel(BorderLayout())
        val textArea = JTextArea(suggestions).apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
        }
        panel.add(JScrollPane(textArea), BorderLayout.CENTER)

        Messages.showMessageDialog(
            panel,
            "AI refactoring suggestions",
            "AI Refactoring Suggestions",
            Messages.getInformationIcon()
        )
    }

    private fun handleError(dialog: RefactorDialog, e: Exception) {
        dialog.updateProgress("Error: ${e.message}", 100)
    }

    private class RefactorDialog(project: Project) : DialogWrapper(project) {
        private val progressBar = JProgressBar(0, 100)
        private val outputArea = JTextArea(15, 50).apply {
            isEditable = false
        }

        init {
            init()
            progressBar.isStringPainted = true
        }

        fun updateProgress(message: String, value: Int) {
            progressBar.value = value
            outputArea.append("$message\n")
        }

        override fun createCenterPanel(): JComponent {
            return JPanel(BorderLayout()).apply {
                add(progressBar, BorderLayout.NORTH)
                add(JScrollPane(outputArea), BorderLayout.CENTER)
            }
        }
    }
}

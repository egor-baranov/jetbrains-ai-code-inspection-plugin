package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory


class CodeInspectionWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val codeInspectionToolWindow = CodeInspectionToolWindow(toolWindow)
        val content = ContentFactory.getInstance().createContent(
            codeInspectionToolWindow.getContent(),
            null,
            false
        )
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.ui.hint

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.inspection.InspectionService
import com.intellij.codeInsight.hints.*
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import javax.swing.JComponent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class ComplexityInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("complexity.hint")

    override val name: String = "ComplexityHintProvider"

    override val previewText: String = "ComplexityHintProvider"

    override fun createSettings() = NoSettings()

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector? = editor.project
        ?.let {
            ComplexityInlayHintsCollector(editor, InspectionService.getInstance(it))
        }

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent {
                return JPanel()
            }

            override fun reset() {}

            override val mainCheckboxText: String
                get() = "Show hints"

            override val cases: List<ImmediateConfigurable.Case>
                get() = emptyList()
        }
}
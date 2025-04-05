package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class PluginConfigurable : SearchableConfigurable {

    private var mySettingsComponent: JPanel? = null
    private var textField: JBTextField? = null

    // Unique ID for the configurable (used when opening programmatically)
    override fun getId(): String = "my.plugin.settings"

    // Display name in the settings dialog
    @NlsContexts.ConfigurableName
    override fun getDisplayName(): String = "My Plugin Settings"

    // Create the settings UI
    override fun createComponent(): JComponent {
        val settings = PluginSettingsState.getInstance()
        textField = JBTextField(settings.someSetting, 20)
        
        mySettingsComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("OpenAI API key:", textField!!)
            .panel
        return mySettingsComponent!!
    }

    // Check if settings were modified
    override fun isModified(): Boolean {
        val settings = PluginSettingsState.getInstance()
        return textField?.text != settings.someSetting
    }

    // Apply changes (save)
    override fun apply() {
        val settings = PluginSettingsState.getInstance()
        settings.someSetting = textField?.text ?: ""
    }

    // Reset to saved state
    override fun reset() {
        val settings = PluginSettingsState.getInstance()
        textField?.text = settings.someSetting
    }
}
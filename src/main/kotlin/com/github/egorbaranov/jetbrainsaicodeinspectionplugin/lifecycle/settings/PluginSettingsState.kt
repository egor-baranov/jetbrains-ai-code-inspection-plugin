package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "MyPluginSettings", storages = [Storage("my-plugin-settings.xml")])
class PluginSettingsState : PersistentStateComponent<PluginSettingsState> {

    var someSetting: String = ""

    override fun getState(): PluginSettingsState = this
    override fun loadState(state: PluginSettingsState) {
        someSetting = state.someSetting
    }

    companion object {
        fun getInstance(): PluginSettingsState =
            ApplicationManager.getApplication().getService(PluginSettingsState::class.java)
    }
}
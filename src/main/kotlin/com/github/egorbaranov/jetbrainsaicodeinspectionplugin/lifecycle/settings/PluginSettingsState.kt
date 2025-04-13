package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "MyPluginSettings",
    storages = [Storage("my-plugin-settings.xml")]
)
class PluginSettingsState : PersistentStateComponent<PluginSettingsState> {

    var apiKey: String = ""
    var apiUrl: String = DEFAULT_API_URL

    override fun getState(): PluginSettingsState = this

    override fun loadState(state: PluginSettingsState) {
        state.copyStateTo(this)
    }

    private fun copyStateTo(target: PluginSettingsState) {
        target.apiKey = this.apiKey
        target.apiUrl = this.apiUrl
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions"

        fun getInstance(): PluginSettingsState =
            ApplicationManager.getApplication().getService(PluginSettingsState::class.java)
    }
}
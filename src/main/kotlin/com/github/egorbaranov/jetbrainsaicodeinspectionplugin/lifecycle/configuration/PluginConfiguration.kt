package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.configuration

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.OpenAIKeyStorage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ApplicationComponent

class PluginConfiguration : ApplicationComponent {
    fun getSettings(): OpenAIKeyStorage {
        return ApplicationManager.getApplication()
            .getService(OpenAIKeyStorage::class.java)
    }
}
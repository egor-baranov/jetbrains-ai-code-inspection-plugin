package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services

import com.intellij.openapi.components.Service

@Service
class OpenAIKeyStorage {
    var apiKey: String = ""
}
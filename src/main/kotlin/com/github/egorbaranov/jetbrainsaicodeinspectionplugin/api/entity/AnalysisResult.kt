package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity

data class AnalysisResult(
    val content: String? = null,
    val actions: List<Action> = emptyList(),
    val error: String? = null
)
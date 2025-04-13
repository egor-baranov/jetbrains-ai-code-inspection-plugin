package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai

data class OpenAIError(
    val message: String,
    val type: String,
    val param: String?,
    val code: String?
)
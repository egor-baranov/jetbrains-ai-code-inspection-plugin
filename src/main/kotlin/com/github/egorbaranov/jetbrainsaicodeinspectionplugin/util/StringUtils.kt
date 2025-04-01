package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.util

object StringUtils {
    fun extractCode(response: String): String {
        val codePattern = Regex("```(?:\\w+)?\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        return codePattern.findAll(response)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .joinToString("\n\n")
    }
}
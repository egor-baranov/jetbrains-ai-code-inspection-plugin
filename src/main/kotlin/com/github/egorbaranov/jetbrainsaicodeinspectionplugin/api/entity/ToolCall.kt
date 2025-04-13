package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity

data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCall
) {
    data class FunctionCall(
        val name: String,
        val arguments: String
    )
}
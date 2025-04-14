package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.openai

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.entity.ToolCall
import com.google.gson.annotations.SerializedName

data class Message(
    val role: String,
    val content: String,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null
)
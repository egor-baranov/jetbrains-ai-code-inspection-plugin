package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.services.metrics

import kotlinx.datetime.Clock

data class Metric(
    val id: MetricID,
    val params: Map<String, String>,
    val timestamp: String
) {

    enum class MetricID {
        EXECUTE,
        INTERRUPT,
        GENERATION_SUCCESS,
        GENERATION_FAILED,
        APPLY_FIX,
        IGNORE_FIX,
        DELETE_INSPECTION,
        DELETE_FILE,
        CLEAR_ALL,
        EDIT_PROMPT,
        ERROR,
        SETTINGS,
        RELOAD,
        OPEN_FILE,
        EXPAND
    }

    companion object {
        fun new(id: MetricID, params: Map<String, String>) = Metric(
            id = id,
            params = params,
            timestamp = Clock.System.now().toString()
        ).also {
            println("new metric: $it")
        }
    }
}
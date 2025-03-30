package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.document

import com.github.egorbaranov.jetbrainsaicodeinspectionplugin.api.OpenAIClient
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

class DocumentChangeListener : DocumentListener {
    private val scheduler = Executors.newScheduledThreadPool(1)
    private var scheduledAnalysis: ScheduledFuture<*>? = null
    private val openAIClient = OpenAIClient()
    private val debounceDelay = 2000L // 2 seconds delay

    override fun documentChanged(event: DocumentEvent) {
        println("document changed: $event")
        // Cancel previous scheduled task
        scheduledAnalysis?.cancel(false)
    }
}
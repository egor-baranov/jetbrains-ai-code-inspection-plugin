package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.document

import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

class DocumentChangeListener : DocumentListener {
    private val scheduler = Executors.newScheduledThreadPool(1)
    private var scheduledAnalysis: ScheduledFuture<*>? = null
    private val debounceDelay = 2000L

    override fun documentChanged(event: DocumentEvent) {
        println("document changed: $event")
        scheduledAnalysis?.cancel(false)
    }
}
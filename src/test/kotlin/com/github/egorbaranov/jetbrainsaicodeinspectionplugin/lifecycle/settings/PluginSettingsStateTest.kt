package com.github.egorbaranov.jetbrainsaicodeinspectionplugin.lifecycle.settings

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll

class PluginSettingsStateTest : BasePlatformTestCase() {

    fun `test getState returns current instance`() {
        val settings = PluginSettingsState().apply {
            apiKey = "test-key"
            apiUrl = "http://custom.url"
        }

        val state = settings.state

        assertEquals(settings, state)
        assertEquals("test-key", state.apiKey)
        assertEquals("http://custom.url", state.apiUrl)
    }

    fun `test loadState copies values correctly`() {
        val source = PluginSettingsState().apply {
            apiKey = "source-key"
            apiUrl = "http://source.url"
        }

        val target = PluginSettingsState().apply {
            apiKey = "initial-key"
            apiUrl = "http://initial.url"
        }

        target.loadState(source)

        assertEquals("source-key", target.apiKey)
        assertEquals("http://source.url", target.apiUrl)
    }

    fun `test default values are set correctly`() {
        val settings = PluginSettingsState()

        assertEquals("", settings.apiKey)
        assertEquals(PluginSettingsState.DEFAULT_API_URL, settings.apiUrl)
    }

    fun `test getInstance retrieves service from application`() {
        mockkStatic(ApplicationManager::class)
        val mockApplication = mockk<Application>()
        val expectedSettings = PluginSettingsState()

        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.getService(PluginSettingsState::class.java) } returns expectedSettings

        val actualSettings = PluginSettingsState.getInstance()

        assertEquals(expectedSettings, actualSettings)
        unmockkAll()
    }

    fun `test copyStateTo copies all fields`() {
        val source = PluginSettingsState().apply {
            apiKey = "copy-key"
            apiUrl = "http://copy.url"
        }

        val target = PluginSettingsState()

        source.copyStateTo(target)

        assertEquals("copy-key", target.apiKey)
        assertEquals("http://copy.url", target.apiUrl)
    }

    fun `test loadState handles empty values`() {
        val source = PluginSettingsState().apply {
            apiKey = ""
            apiUrl = ""
        }

        val target = PluginSettingsState().apply {
            apiKey = "initial"
            apiUrl = "http://initial"
        }

        target.loadState(source)

        assertEquals("", target.apiKey)
        assertEquals("", target.apiUrl)
    }

    fun `test persistence of modified values`() {
        val original = PluginSettingsState().apply {
            apiKey = "original-key"
            apiUrl = "http://original.url"
        }

        val modified = PluginSettingsState().apply {
            loadState(original)
            apiKey = "modified-key"
            apiUrl = "http://modified.url"
        }

        original.loadState(modified)

        assertEquals("modified-key", original.apiKey)
        assertEquals("http://modified.url", original.apiUrl)
    }
}
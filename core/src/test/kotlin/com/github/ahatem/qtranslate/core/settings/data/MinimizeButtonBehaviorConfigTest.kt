package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The minimize setting needs no migration: the serializer default carries older files. */
class MinimizeButtonBehaviorConfigTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `default configuration hides to tray`() {
        assertEquals(MinimizeButtonBehavior.HIDE_TO_TRAY, Configuration().minimizeButtonBehavior)
        assertEquals(MinimizeButtonBehavior.HIDE_TO_TRAY, Configuration.DEFAULT.minimizeButtonBehavior)
    }

    @Test
    fun `older config without minimize field falls back to HIDE_TO_TRAY`() {
        val older = """{"configVersion":1,"closeButtonBehavior":"MINIMIZE_TO_TRAY"}"""
        val config = json.decodeFromString<Configuration>(older)
        assertEquals(MinimizeButtonBehavior.HIDE_TO_TRAY, config.minimizeButtonBehavior)
        assertEquals(CloseButtonBehavior.MINIMIZE_TO_TRAY, config.closeButtonBehavior)
    }

    @Test
    fun `both behaviors round trip`() {
        for (behavior in MinimizeButtonBehavior.entries) {
            val encoded = json.encodeToString(
                Configuration.serializer(),
                Configuration(minimizeButtonBehavior = behavior)
            )
            assertEquals(behavior, json.decodeFromString<Configuration>(encoded).minimizeButtonBehavior)
        }
    }

    @Test
    fun `minimize and close behaviors are configured independently`() {
        val config = Configuration(
            minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR,
            closeButtonBehavior = CloseButtonBehavior.MINIMIZE_TO_TRAY,
        )
        val decoded = json.decodeFromString<Configuration>(json.encodeToString(Configuration.serializer(), config))
        assertEquals(MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR, decoded.minimizeButtonBehavior)
        assertEquals(CloseButtonBehavior.MINIMIZE_TO_TRAY, decoded.closeButtonBehavior)
    }
}

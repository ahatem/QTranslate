package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The minimize setting needs no migration: the serializer default carries older files. */
class MinimizeButtonBehaviorConfigTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    @Test
    fun `default configuration hides to tray`() {
        assertEquals(MinimizeButtonBehavior.HIDE_TO_TRAY, Configuration().minimizeButtonBehavior)
        assertEquals(MinimizeButtonBehavior.HIDE_TO_TRAY, Configuration.DEFAULT.minimizeButtonBehavior)
    }

    @Test
    fun `a fresh installation starts on taskbar minimize`() {
        assertEquals(
            MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR,
            Configuration.FRESH_INSTALL.minimizeButtonBehavior,
        )
        // Nothing else changed, so a fresh install is the stored default plus this one decision.
        assertEquals(
            Configuration.DEFAULT.copy(minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR),
            Configuration.FRESH_INSTALL,
        )
    }

    @Test
    fun `an installation with no stored configuration starts on taskbar minimize`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-fresh-install").toFile()
        val repository = SettingsRepository(directory, json, logger)
        try {
            assertEquals(
                MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR,
                repository.loadInitialConfiguration().minimizeButtonBehavior,
            )
        } finally {
            repository.storageScope.cancel()
        }
    }

    @Test
    fun `older config without minimize field falls back to HIDE_TO_TRAY`() {
        // The writer omits values equal to the default, so a stored configuration with no minimize
        // field is a user who never changed it — and changing the default must not rewrite them.
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

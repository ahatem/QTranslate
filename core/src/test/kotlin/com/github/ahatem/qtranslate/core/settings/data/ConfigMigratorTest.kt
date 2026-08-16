package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v3 → v4 step rewrites every persisted service id, so a mistake here does not throw — it
 * quietly resets the services a user chose. These cover the shapes that actually appear in a
 * configuration file.
 */
class ConfigMigratorTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun v3(
        selected: Map<ServiceRole, String?> = emptyMap(),
        disabled: Set<String> = emptySet()
    ) = Configuration.DEFAULT.copy(
        configVersion = 3,
        servicePresets = listOf(ServicePreset("preset-1", "Default", selected)),
        activeServicePresetId = "preset-1",
        disabledServices = disabled
    )

    @Test
    fun `preset selections are rewritten to composed ids`() {
        val migrated = ConfigMigrator.migrate(
            v3(
                selected = mapOf(
                    ServiceRole.TRANSLATOR to "google-translator",
                    ServiceRole.DICTIONARY to "wikimedia-wiktionary",
                    ServiceRole.TTS to "bing-tts"
                )
            ),
            logger
        )

        val selected = migrated.servicePresets.single().selectedServices
        assertEquals("google-services:default:google-translator", selected[ServiceRole.TRANSLATOR])
        assertEquals("wikimedia-reference:default:wikimedia-wiktionary", selected[ServiceRole.DICTIONARY])
        assertEquals("bing-services:default:bing-tts", selected[ServiceRole.TTS])
    }

    @Test
    fun `a null selection stays null rather than becoming a broken id`() {
        val migrated = ConfigMigrator.migrate(
            v3(selected = mapOf(ServiceRole.OCR to null)),
            logger
        )
        assertEquals(null, migrated.servicePresets.single().selectedServices[ServiceRole.OCR])
    }

    @Test
    fun `disabled entries are rewritten but capability sentinels are left alone`() {
        val migrated = ConfigMigrator.migrate(
            v3(disabled = setOf("google-ocr", "type:SUMMARIZER")),
            logger
        )

        assertTrue("google-services:default:google-ocr" in migrated.disabledServices)
        // The sentinel disables a whole capability and is not a service id at all.
        assertTrue("type:SUMMARIZER" in migrated.disabledServices)
    }

    @Test
    fun `an unrecognised id is left untouched`() {
        // A third-party plugin, or one the user has since removed. Guessing at a plugin id would
        // produce a selection pointing at something that does not exist.
        val migrated = ConfigMigrator.migrate(
            v3(selected = mapOf(ServiceRole.TRANSLATOR to "someone-elses-translator")),
            logger
        )
        assertEquals(
            "someone-elses-translator",
            migrated.servicePresets.single().selectedServices[ServiceRole.TRANSLATOR]
        )
    }

    @Test
    fun `migrating twice changes nothing the second time`() {
        val once = ConfigMigrator.migrate(
            v3(
                selected = mapOf(ServiceRole.TRANSLATOR to "google-translator"),
                disabled = setOf("google-ocr", "type:SUMMARIZER")
            ),
            logger
        )
        val twice = ConfigMigrator.migrate(once, logger)
        assertEquals(once, twice)
    }

    @Test
    fun `a v1 config arrives at the current version with its ids rewritten`() {
        // The oldest shape still in the wild: it goes through every step in one pass.
        val migrated = ConfigMigrator.migrate(
            v3(selected = mapOf(ServiceRole.TRANSLATOR to "google-translator"))
                .copy(configVersion = 1, hotkeys = emptyList()),
            logger
        )

        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.configVersion)
        assertEquals(
            "google-services:default:google-translator",
            migrated.servicePresets.single().selectedServices[ServiceRole.TRANSLATOR]
        )
        // The hotkey steps still ran on the way through.
        assertEquals(HotkeyBinding.DEFAULTS.size, migrated.hotkeys.size)
    }

    @Test
    fun `a fresh configuration is already current and needs no migration`() {
        val fresh = Configuration.DEFAULT
        assertEquals(ConfigMigrator.CURRENT_VERSION, fresh.configVersion)
        assertEquals(fresh, ConfigMigrator.migrate(fresh, logger))
    }

    @Test
    fun `the default preset names services the registry will actually have`() {
        // The defaults are written by hand; this fails if they drift from the composed form.
        Configuration.DEFAULT.servicePresets.single().selectedServices.values
            .filterNotNull()
            .forEach { id ->
                assertEquals(3, id.split(':').size, "default selection '$id' is not a composed service id")
            }
    }
}

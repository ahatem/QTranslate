package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The auto-copy setting needs no migration: the serializer default carries older files, so an
 * existing installation keeps the clipboard to itself until the setting is turned on.
 */
class AutoCopyTranslationConfigTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `auto copy is off by default`() {
        assertEquals(AutoCopyTranslation.OFF, Configuration().autoCopyTranslation)
        assertEquals(AutoCopyTranslation.OFF, Configuration.DEFAULT.autoCopyTranslation)
    }

    @Test
    fun `older config without the field falls back to OFF`() {
        val older = """{"configVersion":7,"isInstantTranslationEnabled":true}"""
        val config = json.decodeFromString<Configuration>(older)
        assertEquals(AutoCopyTranslation.OFF, config.autoCopyTranslation)
        assertEquals(true, config.isInstantTranslationEnabled, "and the rest of the file still reads")
    }

    @Test
    fun `every mode round trips`() {
        for (mode in AutoCopyTranslation.entries) {
            val encoded = json.encodeToString(
                Configuration.serializer(),
                Configuration(autoCopyTranslation = mode)
            )
            assertEquals(mode, json.decodeFromString<Configuration>(encoded).autoCopyTranslation)
        }
    }

    @Test
    fun `the mode is stored by name so renaming it would be a breaking change`() {
        // Written with defaults so the value is actually present in the output.
        val writing = Json { encodeDefaults = true }
        for (mode in AutoCopyTranslation.entries) {
            val encoded = writing.encodeToString(
                Configuration.serializer(),
                Configuration(autoCopyTranslation = mode)
            )
            assertEquals(
                true,
                encoded.contains("\"autoCopyTranslation\":\"${mode.name}\""),
                "$mode should be readable as a plain name, got: $encoded"
            )
        }
    }

    @Test
    fun `the default is not written to disk`() {
        // Configuration is stored without its defaults, so a user who never chose a mode has no
        // key at all. That is also what keeps the value invisible to older builds.
        val encoded = Json {}.encodeToString(Configuration.serializer(), Configuration.DEFAULT)
        assertEquals(false, encoded.contains("autoCopyTranslation"), "got: $encoded")
    }

    @Test
    fun `the setting is independent of instant translation`() {
        val config = Configuration(
            autoCopyTranslation = AutoCopyTranslation.ALL,
            isInstantTranslationEnabled = false,
        )
        val decoded = json.decodeFromString<Configuration>(json.encodeToString(Configuration.serializer(), config))
        assertEquals(AutoCopyTranslation.ALL, decoded.autoCopyTranslation)
        assertEquals(false, decoded.isInstantTranslationEnabled)
    }
}

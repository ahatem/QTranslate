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
    fun `older config without the field falls back to OFF`() {
        val older = """{"configVersion":7,"isInstantTranslationEnabled":true}"""
        val config = json.decodeFromString<Configuration>(older)
        assertEquals(AutoCopyTranslation.OFF, config.autoCopyTranslation)
        assertEquals(true, config.isInstantTranslationEnabled, "and the rest of the file still reads")
    }
}

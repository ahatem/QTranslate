package com.github.ahatem.qtranslate.plugins.libretranslate

import kotlin.test.Test
import kotlin.test.assertEquals

class LibreTranslateSettingsTest {
    @Test
    fun `normalizes local instance URL`() {
        val settings = LibreTranslateSettings(instanceUrl = "  http://localhost:5000///  ")

        assertEquals("http://localhost:5000", settings.normalizedInstanceUrl())
    }
}

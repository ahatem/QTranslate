package com.github.ahatem.qtranslate.ui.swing.settings.panels

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguagesPanelDisplayNameTest {

    @Test
    fun `regional Portuguese carries its country and generic Portuguese stays plain`() {
        val expected = mapOf(
            "pt-BR" to "Portuguese (Brazil)",
            "pt-PT" to "Portuguese (Portugal)",
            "pt" to "Portuguese",
        )
        for ((tag, label) in expected) {
            assertEquals(label, settingsPanelLocalizedName(tag), tag)
        }
    }
}

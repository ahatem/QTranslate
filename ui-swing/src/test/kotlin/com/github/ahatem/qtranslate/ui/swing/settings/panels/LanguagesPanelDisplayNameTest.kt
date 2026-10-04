package com.github.ahatem.qtranslate.ui.swing.settings.panels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LanguagesPanelDisplayNameTest {

    @Test
    fun `regional Portuguese renders with country qualifier in English`() {
        assertEquals("Portuguese (Brazil)", settingsPanelLocalizedName("pt-BR"))
        assertEquals("Portuguese (Portugal)", settingsPanelLocalizedName("pt-PT"))
    }

    @Test
    fun `regional variants produce distinct panel labels`() {
        assertNotEquals(settingsPanelLocalizedName("pt-BR"), settingsPanelLocalizedName("pt-PT"))
    }

    @Test
    fun `generic Portuguese keeps its plain label`() {
        assertEquals("Portuguese", settingsPanelLocalizedName("pt"))
    }
}

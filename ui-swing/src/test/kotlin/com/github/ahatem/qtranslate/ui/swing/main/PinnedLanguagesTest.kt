package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.ui.swing.quicktranslate.buildQuickTranslateDialogState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Pinned Languages must narrow every language picker, not only the main window's: the quick
 * translate popup used to be handed the unfiltered state and listed every language.
 */
class PinnedLanguagesTest {

    private class SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val all = listOf("auto", "en", "es", "eu", "ru", "uk").map(::LanguageCode)
    private val state = MainState(availableLanguages = all)

    @Test
    fun `no pinned languages keeps every language`() {
        assertSame(state, state.withPinnedLanguages(emptyList()))
    }

    @Test
    fun `pinned languages narrow the list and keep auto`() {
        assertEquals(
            listOf("auto", "en", "ru").map(::LanguageCode),
            state.withPinnedLanguages(listOf("ru", "en")).availableLanguages
        )
    }

    @Test
    fun `the quick translate popup offers only the pinned languages`() {
        val dir = java.nio.file.Files.createTempDirectory("qtranslate-pinned-test").toFile()
        val dialogState = buildQuickTranslateDialogState(
            state.withPinnedLanguages(listOf("en", "es")),
            Configuration(),
            LocalizationManager(dir, LanguageTomlParser(), SilentLogger())
        )
        assertEquals(listOf("auto", "en", "es").map(::LanguageCode), dialogState.availableLanguages)
    }
}

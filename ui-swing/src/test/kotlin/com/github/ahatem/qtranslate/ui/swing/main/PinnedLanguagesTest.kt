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
import kotlin.test.assertTrue

/**
 * Pinned Languages is a target-language preference: it narrows what the user can translate
 * *into*, and never what they can translate *from*.
 *
 * The hint on the settings page has always said "languages to show in the target language
 * picker". The implementation used to narrow every picker instead, which hid source languages
 * the user had not asked to hide, so the two now agree — in the main window and in Quick
 * Translate, which must not disagree with it.
 */
class PinnedLanguagesTest {

    private class SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val all = listOf("auto", "en", "es", "eu", "ru", "uk").map(::LanguageCode)
    private val state = MainState(availableLanguages = all, targetLanguage = LanguageCode("en"))

    @Test
    fun `no pinned languages offers every language`() {
        val choices = state.languageChoices(emptyList())
        assertEquals(all, choices.sourceLanguages, "the source picker offers everything, Auto included")
        assertEquals(
            all.filter { it != LanguageCode.AUTO },
            choices.targetLanguages,
            "Auto is never a target",
        )
    }

    @Test
    fun `pins narrow the target picker and leave the source picker alone`() {
        val choices = state.languageChoices(listOf("ru", "es"))
        assertEquals(all, choices.sourceLanguages, "pinning a language never removes a source language")
        assertEquals(
            listOf("en", "es", "ru").map(::LanguageCode),
            choices.targetLanguages,
            "pinned targets keep the translator's order, and the language in use stays available",
        )
    }

    @Test
    fun `the current target stays selectable even when it is not pinned`() {
        val choices = MainState(availableLanguages = all, targetLanguage = LanguageCode("uk"))
            .languageChoices(listOf("en"))
        assertTrue(
            LanguageCode("uk") in choices.targetLanguages,
            "the picker must never hide the language the translation actually uses",
        )
        assertEquals(listOf("en", "uk").map(::LanguageCode), choices.targetLanguages)
    }

    @Test
    fun `a pin the translator does not support contributes nothing`() {
        val choices = state.languageChoices(listOf("ja", "en"))
        assertEquals(listOf("en").map(::LanguageCode), choices.targetLanguages)
    }

    @Test
    fun `the quick translate popup uses the same target semantics`() {
        val dir = java.nio.file.Files.createTempDirectory("qtranslate-pinned-test").toFile()
        val dialogState = buildQuickTranslateDialogState(
            state,
            Configuration(pinnedLanguages = listOf("en", "es")),
            LocalizationManager(dir, LanguageTomlParser(), SilentLogger())
        )
        assertEquals(all, dialogState.availableLanguages, "the popup's source picker stays complete")
        assertEquals(listOf("en", "es").map(::LanguageCode), dialogState.targetLanguages)
    }
}

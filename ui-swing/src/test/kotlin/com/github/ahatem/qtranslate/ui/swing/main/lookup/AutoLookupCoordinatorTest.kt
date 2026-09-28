package com.github.ahatem.qtranslate.ui.swing.main.lookup

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.LookupTool
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoLookupCoordinatorTest {
    private class Harness(config: Configuration, initial: MainState, var mainVisible: Boolean = true) {
        val main = MutableStateFlow(initial)
        val settings = MutableStateFlow(SettingsState.initial(config))
        val intents = Channel<MainIntent>(Channel.UNLIMITED)
        val dockWords = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
        val coordinator = AutoLookupCoordinator(
            mainState = main,
            settingsState = settings,
            dispatch = { intents.trySend(it).getOrThrow() },
            isMainVisible = { mainVisible },
            setDictionarySearchWord = {
                dockWords.trySend(it to SwingUtilities.isEventDispatchThread()).getOrThrow()
            },
        )

        fun finish(input: String = "hello", translated: String = "hola") {
            main.value = main.value.copy(isLoading = true, inputText = input)
            main.value = main.value.copy(isLoading = false, translatedText = translated)
        }

        suspend fun nextIntent(): MainIntent = withTimeout(5_000) { intents.receive() }

        fun assertNoMoreActions() {
            assertTrue(intents.tryReceive().isFailure)
            assertTrue(dockWords.tryReceive().isFailure)
        }
    }

    private fun check(
        config: Configuration = Configuration.DEFAULT,
        initial: MainState = MainState(),
        visible: Boolean = true,
        block: suspend Harness.() -> Unit,
    ) = runBlocking {
        val harness = Harness(config, initial, visible)
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            harness.coordinator.observe()
        }
        try {
            harness.block()
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `idle changes do not trigger lookup`() = check {
        main.value = main.value.copy(inputText = "hello", translatedText = "hola")
        main.value = main.value.copy(inputText = "hello!")
        assertNoMoreActions()
    }

    @Test
    fun `translation completion looks up the translated word and alternate`() = check {
        finish()
        assertEquals(
            MainIntent.UpdateInlineDefinition("hola", LanguageCode.ARABIC, "hello", LanguageCode.ENGLISH),
            nextIntent()
        )
        assertNoMoreActions()
    }

    @Test
    fun `translated snapshot is trimmed before updating the inline definition`() = check {
        finish(translated = " hola ")
        assertEquals(
            MainIntent.UpdateInlineDefinition("hola", LanguageCode.ARABIC, "hello", LanguageCode.ENGLISH),
            nextIntent()
        )
        assertNoMoreActions()
    }

    @Test
    fun `disabled auto lookup ignores completion but still dismisses an unpinned popup`() =
        check(Configuration.DEFAULT.copy(isDictionaryAutoPopupEnabled = false)) {
            main.value = main.value.copy(isLoading = true, isQuickDictionaryVisible = true)
            assertEquals(MainIntent.HideQuickDictionary, nextIntent())
            main.value = main.value.copy(isLoading = false, inputText = "hello", translatedText = "hola")
            assertNoMoreActions()
        }

    @Test
    fun `source preference uses the resolved source language`() =
        check(Configuration.DEFAULT.copy(dictionaryAutoSource = DictionaryAutoSource.SOURCE)) {
            main.value = main.value.copy(sourceLanguage = LanguageCode.AUTO, detectedSourceLanguage = LanguageCode.FRENCH)
            finish()
            assertEquals(
                MainIntent.UpdateInlineDefinition("hello", LanguageCode.FRENCH, "hola", LanguageCode.ARABIC),
                nextIntent()
            )
            assertNoMoreActions()
        }

    @Test
    fun `source snapshot is trimmed before updating the inline definition`() =
        check(Configuration.DEFAULT.copy(dictionaryAutoSource = DictionaryAutoSource.SOURCE)) {
            finish(input = " hello ")
            assertEquals(
                MainIntent.UpdateInlineDefinition("hello", LanguageCode.ENGLISH, "hola", LanguageCode.ARABIC),
                nextIntent()
            )
            assertNoMoreActions()
        }

    @Test
    fun `invalid primary words clear the definition`() {
        for (word in listOf("", "  ", "a", "two words", "two\twords")) {
            check {
                finish(translated = word)
                assertEquals(MainIntent.UpdateInlineDefinition(""), nextIntent())
                assertNoMoreActions()
            }
        }
    }

    @Test
    fun `invalid alternate is omitted without clearing a valid primary`() = check {
        finish(input = "two words")
        assertEquals(
            MainIntent.UpdateInlineDefinition("hola", LanguageCode.ARABIC, "", LanguageCode.ENGLISH),
            nextIntent()
        )
        assertNoMoreActions()
    }

    @Test
    fun `translation start dismisses only an unpinned quick dictionary`() {
        for (pinned in listOf(false, true)) {
            check(initial = MainState(isQuickDictionaryVisible = true, isQuickDictionaryPinned = pinned)) {
                main.value = main.value.copy(isLoading = true)
                if (pinned) assertNoMoreActions()
                else {
                    assertEquals(MainIntent.HideQuickDictionary, nextIntent())
                    assertNoMoreActions()
                }
            }
        }
    }

    @Test
    fun `open visible dictionary refreshes on completion on the Swing thread`() =
        check(initial = MainState(isLookupDockOpen = true)) {
            finish()
            assertEquals(
                MainIntent.UpdateInlineDefinition("hola", LanguageCode.ARABIC, "hello", LanguageCode.ENGLISH),
                nextIntent()
            )
            assertEquals("hola" to true, withTimeout(5_000) { dockWords.receive() })
            assertEquals(MainIntent.LookupWord("hola", LanguageCode.ARABIC), nextIntent())
            assertNoMoreActions()
        }

    @Test
    fun `closed or other-tool dock is not opened automatically`() {
        for (initial in listOf(MainState(), MainState(isLookupDockOpen = true, lookupDockTool = LookupTool.IMAGES))) {
            check(initial = initial) {
                finish()
                assertTrue(nextIntent() is MainIntent.UpdateInlineDefinition)
                assertNoMoreActions()
            }
        }
    }

    @Test
    fun `hidden main window does not update the dock`() =
        check(initial = MainState(isLookupDockOpen = true), visible = false) {
            finish()
            assertTrue(nextIntent() is MainIntent.UpdateInlineDefinition)
            assertNoMoreActions()
        }

    @Test
    fun `same dictionary word skips dock refresh after inline definition`() =
        check(initial = MainState(isLookupDockOpen = true, dictionaryWord = "HOLA")) {
            finish()
            assertTrue(nextIntent() is MainIntent.UpdateInlineDefinition)
            assertNoMoreActions()
        }

    @Test
    fun `settings changes retrigger only with an idle translated result`() = check {
        main.value = main.value.copy(inputText = "hello", translatedText = "hola")
        settings.value = settings.value.copy(
            workingConfiguration = settings.value.workingConfiguration.copy(dictionaryAutoSource = DictionaryAutoSource.SOURCE)
        )
        assertEquals(
            MainIntent.UpdateInlineDefinition("hello", LanguageCode.ENGLISH, "hola", LanguageCode.ARABIC),
            nextIntent()
        )
        assertNoMoreActions()

        main.value = main.value.copy(translatedText = "")
        settings.value = settings.value.copy(
            workingConfiguration = settings.value.workingConfiguration.copy(dictionaryAutoSource = DictionaryAutoSource.TRANSLATED)
        )
        assertNoMoreActions()
    }

    @Test
    fun `enabling auto lookup and opening the dictionary retrigger an idle result`() =
        check(Configuration.DEFAULT.copy(isDictionaryAutoPopupEnabled = false)) {
            main.value = main.value.copy(inputText = "hello", translatedText = "hola")
            settings.value = settings.value.copy(
                workingConfiguration = settings.value.workingConfiguration.copy(isDictionaryAutoPopupEnabled = true)
            )
            assertTrue(nextIntent() is MainIntent.UpdateInlineDefinition)
            assertNoMoreActions()

            main.value = main.value.copy(isLookupDockOpen = true)
            assertTrue(nextIntent() is MainIntent.UpdateInlineDefinition)
            assertEquals("hola" to true, withTimeout(5_000) { dockWords.receive() })
            assertEquals(MainIntent.LookupWord("hola", LanguageCode.ARABIC), nextIntent())
            assertNoMoreActions()
        }

    @Test
    fun `off source setting retains translated word preference`() =
        check(Configuration.DEFAULT.copy(dictionaryAutoSource = DictionaryAutoSource.OFF)) {
            finish()
            assertEquals(
                MainIntent.UpdateInlineDefinition("hola", LanguageCode.ARABIC, "hello", LanguageCode.ENGLISH),
                nextIntent()
            )
            assertNoMoreActions()
        }
}

package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import com.github.ahatem.qtranslate.core.ocr.resolveOcrWorkflow
import com.github.ahatem.qtranslate.core.settings.data.OcrPostSelectionAction
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OcrWorkflowRoutingTest {

    @Test
    fun `the historic default stays show actions`() {
        val settings = OcrSettings()
        assertEquals(OcrPostSelectionAction.SHOW_ACTIONS, settings.postSelectionAction)
        assertEquals(
            OcrPostSelectionAction.SHOW_ACTIONS,
            resolveOcrWorkflow(OcrWorkflowCommand.DEFAULT, settings).action,
        )
        assertEquals(
            OcrTranslationDestination.MAIN_WINDOW,
            settings.translationDestination,
        )
    }

    @Test
    fun `explicit workflow commands override the saved action and destination`() {
        val settings = OcrSettings(
            postSelectionAction = OcrPostSelectionAction.COPY_TEXT,
            translationDestination = OcrTranslationDestination.MAIN_WINDOW,
        )

        assertEquals(
            OcrPostSelectionAction.TRANSLATE,
            resolveOcrWorkflow(OcrWorkflowCommand.TRANSLATE_QUICK, settings).action,
        )
        assertEquals(
            OcrTranslationDestination.QUICK_TRANSLATE,
            resolveOcrWorkflow(OcrWorkflowCommand.TRANSLATE_QUICK, settings).destinationOverride,
        )
        assertEquals(
            OcrTranslationDestination.MAIN_WINDOW,
            resolveOcrWorkflow(OcrWorkflowCommand.TRANSLATE_MAIN, settings).destinationOverride,
        )
        assertEquals(
            OcrPostSelectionAction.OPEN_WORKSPACE,
            resolveOcrWorkflow(OcrWorkflowCommand.OPEN_WORKSPACE, settings).action,
        )
        assertEquals(
            OcrPostSelectionAction.COPY_IMAGE,
            resolveOcrWorkflow(OcrWorkflowCommand.COPY_IMAGE, settings).action,
        )
    }

    @Test
    fun `copy text recognizes preprocesses and copies without translating`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(removeLineBreaks = true),
        )
        try {
            fixture.ocr.responder = {
                Ok(OCRResponse("first line\nsecond line"))
            }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.COPY_TEXT,
                )
            )
            advanceUntilIdle()

            assertEquals(
                listOf(MainEvent.CopyToClipboard("first line second line")),
                fixture.events.filterIsInstance<MainEvent.CopyToClipboard>(),
            )
            assertTrue(fixture.translator.received.isEmpty(), "copy text must not translate")
            assertEquals("", fixture.store.state.value.inputText)
            assertFalse(fixture.store.state.value.isQuickTranslateDialogVisible)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `copy image is cheap and never invokes OCR or translation`() = runTest {
        val fixture = OcrWorkflowFixture(this)
        try {
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.COPY_IMAGE,
                )
            )
            advanceUntilIdle()

            assertEquals(0, fixture.ocr.requests.size)
            assertTrue(fixture.translator.received.isEmpty())
            val event = fixture.events.filterIsInstance<MainEvent.CopyImageToClipboard>().single()
            assertEquals(fixture.image(), event.image)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `open workspace forwards the image and does not recognize it in the store`() = runTest {
        val fixture = OcrWorkflowFixture(this)
        try {
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.OPEN_WORKSPACE,
                )
            )
            advanceUntilIdle()

            val event = fixture.events.filterIsInstance<MainEvent.OpenOcrWorkspace>().single()
            assertFalse(event.autoTranslate)
            assertEquals(null, event.initialText)
            assertEquals(fixture.image(), event.image)
            assertEquals(0, fixture.ocr.requests.size)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `translate to the main window writes canonical input and asks for presentation`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.MAIN_WINDOW,
            ),
        )
        try {
            fixture.ocr.responder = { Ok(OCRResponse("recognized\ntext")) }
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            advanceUntilIdle()

            assertEquals("recognized\ntext", fixture.store.state.value.inputText)
            assertEquals(listOf("recognized\ntext"), fixture.translator.received)
            assertTrue(fixture.events.any { it == MainEvent.ShowMainWindow })
            assertFalse(fixture.store.state.value.isQuickTranslateDialogVisible)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `translate to quick uses the canonical popup route and does not show the main window`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.QUICK_TRANSLATE,
            ),
        )
        try {
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            advanceUntilIdle()

            assertTrue(fixture.store.state.value.isQuickTranslateDialogVisible)
            assertEquals("recognized text", fixture.store.state.value.inputText)
            assertEquals(listOf("recognized text"), fixture.translator.received)
            assertFalse(fixture.events.any { it == MainEvent.ShowMainWindow })
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `an explicit translate to quick overrides the saved main destination`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.MAIN_WINDOW,
            ),
        )
        try {
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.TRANSLATE,
                    destinationOverride = OcrTranslationDestination.QUICK_TRANSLATE,
                )
            )
            advanceUntilIdle()

            assertTrue(fixture.store.state.value.isQuickTranslateDialogVisible)
            assertFalse(fixture.events.any { it == MainEvent.ShowMainWindow })
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `translate to workspace hands the recognized result to the workspace`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.OCR_WORKSPACE,
            ),
        )
        try {
            fixture.ocr.responder = { Ok(OCRResponse("workspace text")) }
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            advanceUntilIdle()

            val event = fixture.events.filterIsInstance<MainEvent.OpenOcrWorkspace>().single()
            assertTrue(event.autoTranslate)
            assertEquals("workspace text", event.initialText)
            assertEquals(fixture.image(), event.image)
            assertTrue(fixture.translator.received.isEmpty(), "the workspace owns its translation")
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `OCR to Quick follows the quick-only auto-copy policy`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.QUICK_TRANSLATE,
            ),
            autoCopy = com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation.QUICK_TRANSLATE_ONLY,
        )
        try {
            val copied = fixture.copiedAfter {
                fixture.store.dispatch(
                    MainIntent.OcrWorkflowImage(
                        image = fixture.image(),
                        action = OcrPostSelectionAction.TRANSLATE,
                    )
                )
            }
            assertEquals("t:recognized text", copied)
            assertEquals("t:recognized text", fixture.store.state.value.translatedText)
            assertTrue(fixture.store.state.value.isQuickTranslateDialogVisible)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a failed OCR translate reveals the main window instead of stranding the user`() = runTest {
        val fixture = OcrWorkflowFixture(this)
        try {
            fixture.ocr.responder = {
                com.github.michaelbull.result.Err(
                    com.github.ahatem.qtranslate.api.plugin.ServiceError.InvalidResponseError("failed")
                )
            }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.TRANSLATE,
                    destinationOverride = OcrTranslationDestination.QUICK_TRANSLATE,
                )
            )
            advanceUntilIdle()

            assertTrue(fixture.events.any { it == MainEvent.ShowMainWindow })
            assertEquals("", fixture.store.state.value.inputText)
            assertFalse(fixture.store.state.value.isQuickTranslateDialogVisible)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `OCR to Main does not copy under the quick-only policy`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.MAIN_WINDOW,
            ),
            autoCopy = com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation.QUICK_TRANSLATE_ONLY,
        )
        try {
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            advanceUntilIdle()

            assertTrue(fixture.events.filterIsInstance<MainEvent.CopyToClipboard>().isEmpty())
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `workspace handoff to quick translates the recognized source exactly once`() = runTest {
        val fixture = OcrWorkflowFixture(this)
        try {
            fixture.store.dispatch(
                MainIntent.SelectSourceLanguage(com.github.ahatem.qtranslate.api.language.LanguageCode.JAPANESE)
            )
            fixture.store.dispatch(
                MainIntent.SelectTargetLanguage(com.github.ahatem.qtranslate.api.language.LanguageCode.ENGLISH)
            )
            // The workspace first translates its recognized source: Japanese -> English.
            fixture.store.dispatch(MainIntent.UpdateInputText("こんにちは"))
            fixture.store.dispatch(MainIntent.Translate("こんにちは"))
            advanceUntilIdle()
            assertEquals("t:こんにちは", fixture.store.state.value.translatedText)

            // "Send to Quick" must send the source text, never the already-English result.
            fixture.store.dispatch(MainIntent.ShowQuickTranslate("こんにちは"))
            advanceUntilIdle()

            assertEquals(listOf("こんにちは", "こんにちは"), fixture.translator.received)
            assertEquals("こんにちは", fixture.store.state.value.inputText)
            assertEquals("t:こんにちは", fixture.store.state.value.translatedText)
            assertTrue(fixture.store.state.value.isQuickTranslateDialogVisible)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a slow main OCR translation cannot overwrite a newer OCR quick result`() = runTest {
        val fixture = OcrWorkflowFixture(this)
        try {
            fixture.ocr.responder = { request ->
                if (request.image.width == 8) {
                    Ok(OCRResponse("slow"))
                } else {
                    Ok(OCRResponse("fast"))
                }
            }
            fixture.translator.beforeTranslate = { text ->
                if (text == "slow") kotlinx.coroutines.delay(10_000)
            }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(1), "png", 8, 8),
                    action = OcrPostSelectionAction.TRANSLATE,
                    destinationOverride = OcrTranslationDestination.MAIN_WINDOW,
                )
            )
            runCurrent()
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(2), "png", 9, 9),
                    action = OcrPostSelectionAction.TRANSLATE,
                    destinationOverride = OcrTranslationDestination.QUICK_TRANSLATE,
                )
            )
            advanceUntilIdle()

            assertEquals("fast", fixture.store.state.value.inputText)
            assertEquals("t:fast", fixture.store.state.value.translatedText)
            assertTrue(fixture.store.state.value.isQuickTranslateDialogVisible)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a superseded OCR request cannot overwrite the current one`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.QUICK_TRANSLATE,
            ),
        )
        try {
            fixture.ocr.responder = { request ->
                if (request.image.width == 8) {
                    // First request is held until after the second has completed.
                    kotlinx.coroutines.delay(10_000)
                    Ok(OCRResponse("stale text"))
                } else {
                    Ok(OCRResponse("current text"))
                }
            }
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(1), "png", 8, 8),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            runCurrent()
            assertEquals(1, fixture.ocr.requests.size, "the first request is genuinely in flight")
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(2), "png", 9, 9),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            advanceUntilIdle()

            assertEquals(2, fixture.ocr.requests.size)
            assertEquals("current text", fixture.store.state.value.inputText)
            assertEquals(listOf("current text"), fixture.translator.received)
        } finally {
            fixture.cleanUp()
        }
    }
}

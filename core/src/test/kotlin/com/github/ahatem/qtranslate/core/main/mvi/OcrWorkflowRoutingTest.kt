package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import com.github.ahatem.qtranslate.core.ocr.resolveOcrWorkflow
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
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

    private class DestinationCase(
        val label: String,
        val saved: OcrTranslationDestination,
        val override: OcrTranslationDestination?,
        val recognized: String,
        val opensQuick: Boolean,
    )

    @Test
    fun `translate routes to the main window or the quick popup`() = runTest {
        val cases = listOf(
            DestinationCase("saved main window", OcrTranslationDestination.MAIN_WINDOW, null, "recognized\ntext", opensQuick = false),
            DestinationCase("saved quick popup", OcrTranslationDestination.QUICK_TRANSLATE, null, "recognized text", opensQuick = true),
            DestinationCase("explicit quick overrides saved main", OcrTranslationDestination.MAIN_WINDOW, OcrTranslationDestination.QUICK_TRANSLATE, "recognized text", opensQuick = true),
        )

        for (case in cases) {
            val fixture = OcrWorkflowFixture(
                this,
                OcrSettings(
                    postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                    translationDestination = case.saved,
                ),
            )
            try {
                fixture.ocr.responder = { Ok(OCRResponse(case.recognized)) }
                fixture.store.dispatch(
                    MainIntent.OcrWorkflowImage(
                        image = fixture.image(),
                        action = OcrPostSelectionAction.TRANSLATE,
                        destinationOverride = case.override,
                    )
                )
                advanceUntilIdle()

                val state = fixture.store.state.value
                assertEquals(case.recognized, state.inputText, case.label)
                assertEquals(listOf(case.recognized), fixture.translator.received, case.label)
                assertEquals(case.opensQuick, state.isQuickTranslateDialogVisible, case.label)
                assertEquals(!case.opensQuick, fixture.events.any { it == MainEvent.ShowMainWindow }, case.label)
            } finally {
                fixture.cleanUp()
            }
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
    fun `OCR translation follows the quick-only auto-copy policy`() = runTest {
        for (destination in listOf(OcrTranslationDestination.QUICK_TRANSLATE, OcrTranslationDestination.MAIN_WINDOW)) {
            val fixture = OcrWorkflowFixture(
                this,
                OcrSettings(
                    postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                    translationDestination = destination,
                ),
                autoCopy = AutoCopyTranslation.QUICK_TRANSLATE_ONLY,
            )
            try {
                val image = {
                    fixture.store.dispatch(
                        MainIntent.OcrWorkflowImage(
                            image = fixture.image(),
                            action = OcrPostSelectionAction.TRANSLATE,
                        )
                    )
                }
                if (destination == OcrTranslationDestination.QUICK_TRANSLATE) {
                    val copied = fixture.copiedAfter { image() }
                    assertEquals("t:recognized text", copied)
                    assertEquals("t:recognized text", fixture.store.state.value.translatedText)
                    assertTrue(fixture.store.state.value.isQuickTranslateDialogVisible)
                } else {
                    image()
                    advanceUntilIdle()
                    assertTrue(fixture.events.filterIsInstance<MainEvent.CopyToClipboard>().isEmpty(), "main window must not copy")
                }
            } finally {
                fixture.cleanUp()
            }
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
            fixture.store.dispatch(MainIntent.UpdateInputText("こんにちは"))
            fixture.store.dispatch(MainIntent.Translate("こんにちは"))
            advanceUntilIdle()
            assertEquals("t:こんにちは", fixture.store.state.value.translatedText)

            // Send to Quick sends the source text, never the already-translated result.
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
    fun `workspace translation is superseded by a newer OCR workflow and does not auto-copy`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.OCR_WORKSPACE,
            ),
            autoCopy = AutoCopyTranslation.ALL,
        )
        try {
            fixture.ocr.responder = { Ok(OCRResponse("slow text")) }
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            advanceUntilIdle()

            val open = fixture.events.filterIsInstance<MainEvent.OpenOcrWorkspace>().single()
            assertTrue(open.autoTranslate)
            assertTrue(open.ocrToken != null, "workspace must learn the capture identity")

            fixture.translator.beforeTranslate = { text ->
                if (text == "slow text") kotlinx.coroutines.delay(10_000)
            }
            var workspaceRequestId: Long? = null
            fixture.store.dispatch(MainIntent.UpdateInputText("slow text"))
            fixture.store.dispatch(
                MainIntent.Translate(
                    text = "slow text",
                    onTranslationClaimed = { workspaceRequestId = it },
                    ocrWorkspaceToken = open.ocrToken,
                )
            )
            runCurrent()
            assertTrue(workspaceRequestId != null)
            assertTrue(fixture.store.state.value.isLoading)

            // B arrives via Copy Image: the newer claim cancels A's translation.
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(4, 5), "png", 9, 9),
                    action = OcrPostSelectionAction.COPY_IMAGE,
                )
            )
            advanceUntilIdle()

            assertTrue(fixture.events.any { it is MainEvent.CopyImageToClipboard })
            assertFalse(fixture.store.state.value.isLoading, "A must not keep loading over B")
            assertEquals("", fixture.store.state.value.translatedText, "A must never publish as current")
            assertTrue(
                fixture.events.filterIsInstance<MainEvent.CopyToClipboard>().isEmpty(),
                "stale A must not overwrite the clipboard after B",
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a workspace translation for a superseded capture is dropped before it starts`() = runTest {
        val fixture = OcrWorkflowFixture(
            this,
            OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.OCR_WORKSPACE,
            ),
        )
        try {
            fixture.ocr.responder = { Ok(OCRResponse("stale text")) }
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = fixture.image(),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            advanceUntilIdle()

            val open = fixture.events.filterIsInstance<MainEvent.OpenOcrWorkspace>().single()
            val staleToken = open.ocrToken
            assertTrue(staleToken != null)

            // A newer workflow takes OCR ownership before the workspace asks.
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(4, 5), "png", 9, 9),
                    action = OcrPostSelectionAction.COPY_IMAGE,
                )
            )
            runCurrent()

            fixture.store.dispatch(
                MainIntent.Translate(text = "stale text", ocrWorkspaceToken = staleToken)
            )
            advanceUntilIdle()

            assertTrue(fixture.translator.received.isEmpty(), "superseded must not translate")
            assertFalse(fixture.store.state.value.isLoading)
            assertEquals("", fixture.store.state.value.translatedText)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `cancel translation if current ignores a superseded request`() = runTest {
        // Cancellation is by request identity, never by equal text.
        val fixture = OcrWorkflowFixture(this)
        try {
            fixture.translator.beforeTranslate = { kotlinx.coroutines.delay(10_000) }
            var firstId: Long? = null
            fixture.store.dispatch(MainIntent.UpdateInputText("hello"))
            fixture.store.dispatch(
                MainIntent.Translate(text = "hello", onTranslationClaimed = { firstId = it })
            )
            runCurrent()
            assertTrue(firstId != null)
            assertTrue(fixture.store.state.value.isLoading)

            // A newer manual request with identical text takes over.
            var secondId: Long? = null
            fixture.store.dispatch(
                MainIntent.Translate(text = "hello", onTranslationClaimed = { secondId = it })
            )
            runCurrent()
            assertTrue(secondId != null && secondId != firstId)

            fixture.store.dispatch(MainIntent.CancelTranslationIfCurrent(firstId!!))
            runCurrent()
            assertTrue(fixture.store.state.value.isLoading, "newer manual must survive the stale cancel")

            fixture.store.dispatch(MainIntent.CancelTranslationIfCurrent(secondId!!))
            runCurrent()
            assertFalse(fixture.store.state.value.isLoading, "the current request must cancel")
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `workspace-local identity is claimed during dispatch so an immediate replacement supersedes it`() = runTest {
        // Replace A before any scheduler work runs: its identity must already be known.
        val fixture = OcrWorkflowFixture(
            this,
            autoCopy = AutoCopyTranslation.ALL,
        )
        try {
            var claimedId: Long? = null
            fixture.store.dispatch(MainIntent.UpdateInputText("slow text"))
            fixture.store.dispatch(
                MainIntent.Translate(text = "slow text", onTranslationClaimed = { claimedId = it })
            )
            assertTrue(claimedId != null, "identity must be known before dispatch returns")
            fixture.store.dispatch(MainIntent.CancelTranslationIfCurrent(claimedId!!))
            advanceUntilIdle()

            assertTrue(fixture.translator.received.isEmpty(), "superseded before start must not reach the provider")
            assertEquals("", fixture.store.state.value.translatedText, "stale A must never publish")
            assertFalse(fixture.store.state.value.isLoading)
            assertTrue(
                fixture.events.filterIsInstance<MainEvent.CopyToClipboard>().isEmpty(),
                "stale A must not auto-copy over the newer workspace input",
            )
        } finally {
            fixture.cleanUp()
        }
    }
}

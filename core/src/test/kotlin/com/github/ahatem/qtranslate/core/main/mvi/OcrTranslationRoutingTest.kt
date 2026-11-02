package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The OCR destination decides which canonical translation path runs, not just where the result is
 * rendered. These tests exercise the real dispatch and request-ownership path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OcrTranslationRoutingTest {

    private val image = ImageData(byteArrayOf(1, 2, 3), "png", 1, 1)

    @Test
    fun `popup destination routes the OCR result through Quick Translate`() = runTest {
        val fixture = OcrTranslationRoutingFixture(
            this,
            ocrText = "ocr\nresult",
            removeLineBreaks = true,
        )
        try {
            fixture.store.dispatch(
                MainIntent.OcrAndTranslateImage(image, OcrTranslationDestination.POPUP)
            )
            advanceUntilIdle()

            val state = fixture.store.state.value
            assertTrue(state.isQuickTranslateDialogVisible, "the popup must be asked for")
            assertEquals("ocr result", state.inputText, "Quick Translate line-break policy must apply")
            assertEquals("t:ocr result", state.translatedText)
            assertEquals(listOf("ocr result"), fixture.translator.received)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `popup destination uses the Quick Translate origin`() = runTest {
        val fixture = OcrTranslationRoutingFixture(
            this,
            ocrText = "ocr result",
            autoCopy = AutoCopyTranslation.QUICK_TRANSLATE_ONLY,
        )
        try {
            val copied = fixture.copiedAfter {
                fixture.store.dispatch(
                    MainIntent.OcrAndTranslateImage(image, OcrTranslationDestination.POPUP)
                )
            }
            advanceUntilIdle()

            assertEquals(
                "t:ocr result",
                copied,
                "Quick-only auto-copy must cover OCR routed to the popup"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `main window destination writes OCR text to the main input and translates`() = runTest {
        val fixture = OcrTranslationRoutingFixture(this, ocrText = "ocr result")
        try {
            fixture.store.dispatch(
                MainIntent.OcrAndTranslateImage(image, OcrTranslationDestination.MAIN_WINDOW)
            )
            advanceUntilIdle()

            val state = fixture.store.state.value
            assertFalse(state.isQuickTranslateDialogVisible, "the popup must stay closed")
            assertEquals("ocr result", state.inputText)
            assertEquals("t:ocr result", state.translatedText)
            assertEquals(listOf("ocr result"), fixture.translator.received)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `the default destination keeps existing OCR callers on the main window`() = runTest {
        val fixture = OcrTranslationRoutingFixture(this, ocrText = "ocr result")
        try {
            fixture.store.dispatch(MainIntent.OcrAndTranslateImage(image))
            advanceUntilIdle()

            val state = fixture.store.state.value
            assertFalse(state.isQuickTranslateDialogVisible)
            assertEquals("t:ocr result", state.translatedText)
        } finally {
            fixture.cleanUp()
        }
    }
}

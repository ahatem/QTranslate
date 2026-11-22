package com.github.ahatem.qtranslate.ui.swing.ocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.ocr.OcrProviderInfo
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceError
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceState
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OcrWorkspacePoliciesTest {
    private val provider = OcrProviderInfo(
        id = "ocr",
        name = "OCR",
        iconPath = null,
        languages = listOf(LanguageCode.ENGLISH),
        isLoadingLanguages = false,
    )
    private val image = ImageData(byteArrayOf(1), "png", 10, 10)
    private val ready = OcrWorkspaceState(providers = listOf(provider), providerId = "ocr", image = image)

    @Test
    fun `a missing provider is explained with a way to fix it before and after an image arrives`() {
        val beforeImage = ocrStatus(OcrWorkspaceState())
        assertEquals("ocr_workspace.no_provider", beforeImage.key)
        assertEquals(OcrStatusAction.OPEN_SETTINGS, beforeImage.action)
        assertNotEquals(OcrStatusTone.ERROR, beforeImage.tone, "nothing has failed yet")

        val afterImage = ocrStatus(OcrWorkspaceState(image = image, error = OcrWorkspaceError.NoProvider))
        assertEquals("ocr_workspace.no_provider", afterImage.key)
        assertEquals(OcrStatusAction.OPEN_SETTINGS, afterImage.action)
        assertEquals(OcrStatusTone.ERROR, afterImage.tone)
    }

    @Test
    fun `work in flight outranks errors and other failures offer no settings action`() {
        assertEquals(OcrStatusTone.BUSY, ocrStatus(ready.copy(isRecognizing = true, error = OcrWorkspaceError.Timeout)).tone)
        assertEquals("ocr_workspace.loading_image", ocrStatus(OcrWorkspaceState(isLoadingImage = true)).key)
        val failed = ocrStatus(ready.copy(error = OcrWorkspaceError.Failed("detail")))
        assertEquals("ocr_workspace.recognition_failed", failed.key)
        assertEquals(OcrStatusAction.NONE, failed.action)
        assertEquals("ocr_workspace.no_text_found", ocrStatus(ready.copy(error = OcrWorkspaceError.NoText)).key)
    }

    @Test
    fun `a finished recognition names its language instead of a vague ready`() {
        val known = ocrStatus(ready.copy(recognizedText = "text", recognizedLanguage = LanguageCode.JAPANESE))
        assertEquals("ocr_workspace.recognized_language", known.key)
        assertEquals(LanguageCode.JAPANESE, known.language)
        assertEquals("ocr_workspace.recognized", ocrStatus(ready.copy(recognizedText = "text")).key)
        assertEquals("ocr_workspace.recognized", ocrStatus(ready.copy(recognizedText = "text", recognizedLanguage = LanguageCode.AUTO)).key)
        assertEquals("ocr_workspace.edited", ocrStatus(ready.copy(recognizedText = "text", isEdited = true)).key)
        assertEquals("ocr_workspace.ready", ocrStatus(ready.copy(image = null)).key)
        assertEquals("ocr_workspace.no_text", ocrStatus(ready).key)
    }

    @Test
    fun `escape stops recognition, then this window's translation, and only then closes`() {
        assertEquals(OcrEscapeAction.CANCEL_RECOGNITION, resolveOcrEscape(isRecognizing = true, isTranslatingHere = true))
        assertEquals(OcrEscapeAction.CANCEL_TRANSLATION, resolveOcrEscape(isRecognizing = false, isTranslatingHere = true))
        assertEquals(OcrEscapeAction.CLOSE, resolveOcrEscape(isRecognizing = false, isTranslatingHere = false))
    }

    @Test
    fun `file choosers stay reachable while typing but a chord never discards typed edits`() {
        for (shortcut in listOf(OcrShortcut.OPEN_IMAGE, OcrShortcut.SAVE_IMAGE)) {
            assertTrue(ocrShortcutAllowed(shortcut, editingText = true, textEdited = true), shortcut.name)
        }
        assertTrue(ocrShortcutAllowed(OcrShortcut.RECOGNIZE, editingText = true, textEdited = false))
        assertFalse(ocrShortcutAllowed(OcrShortcut.RECOGNIZE, editingText = true, textEdited = true))
        assertTrue(ocrShortcutAllowed(OcrShortcut.RECOGNIZE, editingText = false, textEdited = true))
        assertFalse(ocrShortcutAllowed(OcrShortcut.PASTE_IMAGE, editingText = true, textEdited = false), "Ctrl+V belongs to the editor")
        assertTrue(ocrShortcutAllowed(OcrShortcut.PASTE_IMAGE, editingText = false, textEdited = false))
    }

    @Test
    fun `paste in the editor keeps text as text and turns an image-only clipboard into the image`() {
        assertEquals(OcrPasteTarget.TEXT, resolveOcrPaste(clipboardHasText = true, clipboardHasImage = true))
        assertEquals(OcrPasteTarget.TEXT, resolveOcrPaste(clipboardHasText = true, clipboardHasImage = false))
        assertEquals(OcrPasteTarget.TEXT, resolveOcrPaste(clipboardHasText = false, clipboardHasImage = false))
        assertEquals(OcrPasteTarget.IMAGE, resolveOcrPaste(clipboardHasText = false, clipboardHasImage = true))
    }

    @Test
    fun `tooltips name the bound chord`() {
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val tooltip = withShortcut("Recognize", stroke)
        assertTrue(tooltip.startsWith("Recognize ("), tooltip)
        assertTrue(tooltip.endsWith("+${KeyEvent.getKeyText(KeyEvent.VK_R)})"), tooltip)
        assertEquals("Fit (${KeyEvent.getKeyText(KeyEvent.VK_F1)})", withShortcut("Fit", KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0)))
    }
}

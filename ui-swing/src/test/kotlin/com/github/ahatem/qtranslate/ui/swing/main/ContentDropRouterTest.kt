package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContent
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * File-drop routing, without a real drop, window or document dialog.
 *
 * Plain text must fall through untouched — that is what lets a text drag reach the editor
 * under the pointer — so "nothing happens" is pinned as a real outcome, not an omission.
 * The document branch is asserted through an immediate UI runner, so the test stays
 * deterministic while the production default defers to the EDT.
 */
class ContentDropRouterTest {

    private val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, 0xFF336699.toInt())
    }

    @Test
    fun `a picture dispatches the OCR translation intent with the png encoding`() {
        val dispatched = mutableListOf<MainIntent>()
        val opened = mutableListOf<File>()

        ContentDropRouter(dispatched::add, opened::add, deferToUi = { it() })
            .route(DroppedContent.Picture(image))

        val intent = dispatched.single() as MainIntent.OcrAndTranslateImage
        assertEquals(image.toImageData("png"), intent.image)
        assertTrue(opened.isEmpty(), "a picture is not a document")
    }

    @Test
    fun `a document opens the document translation dialog on the ui thread`() {
        val dispatched = mutableListOf<MainIntent>()
        val opened = mutableListOf<File>()
        val document = File("contract.docx")

        ContentDropRouter(dispatched::add, opened::add, deferToUi = { it() })
            .route(DroppedContent.Document(document))

        assertEquals(listOf(document), opened)
        assertTrue(dispatched.isEmpty(), "a document is not OCR'd")
    }

    @Test
    fun `plain text and anything unrecognised is deliberately declined`() {
        val dispatched = mutableListOf<MainIntent>()
        val opened = mutableListOf<File>()

        ContentDropRouter(dispatched::add, opened::add, deferToUi = { it() })
            .route(DroppedContent.None)

        assertTrue(dispatched.isEmpty(), "declined content dispatches nothing")
        assertTrue(opened.isEmpty(), "declined content opens nothing")
    }
}

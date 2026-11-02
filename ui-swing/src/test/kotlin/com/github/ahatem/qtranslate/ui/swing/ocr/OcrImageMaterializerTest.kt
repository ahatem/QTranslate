package com.github.ahatem.qtranslate.ui.swing.ocr

import com.github.ahatem.qtranslate.api.ocr.ImageData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Workspace must not classify, read, decode or encode images on the Swing thread. These tests
 * inject a dispatcher and prove the blocking work is actually dispatched through it.
 */
class OcrImageMaterializerTest {

    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            Dispatchers.IO.dispatch(context, block)
        }
    }

    private val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, 0xFF00FF00.toInt())
    }

    private fun pngBytes(): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(image, "png", out)
        out.toByteArray()
    }

    @Test
    fun `image data decoding is dispatched away from the caller`() = runBlocking {
        val dispatcher = RecordingDispatcher()
        val decoded = OcrImageMaterializer.decode(
            ImageData(pngBytes(), "png", image.width, image.height),
            dispatcher,
        )

        assertNotNull(decoded)
        assertEquals(image.width, decoded.width)
        assertEquals(image.height, decoded.height)
        assertTrue(dispatcher.dispatches > 0)
    }

    @Test
    fun `file reading and png encoding are dispatched away from the caller`() = runBlocking {
        val file = Files.createTempFile("qtranslate-ocr-image", ".png").toFile()
        try {
            ImageIO.write(image, "png", file)
            val dispatcher = RecordingDispatcher()
            val materialized = OcrImageMaterializer.readFile(file, dispatcher)

            assertNotNull(materialized)
            assertEquals(image.width, materialized.image.width)
            assertEquals("png", materialized.data.format)
            assertTrue(dispatcher.dispatches > 0)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `clipboard classification and decoding are dispatched away from the caller`() = runBlocking {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = image
        }
        val dispatcher = RecordingDispatcher()
        val materialized = OcrImageMaterializer.readClipboard(transferable, dispatcher)

        assertNotNull(materialized)
        assertEquals(image.width, materialized.image.width)
        assertTrue(dispatcher.dispatches > 0)
    }

    @Test
    fun `encoding a captured image is dispatched away from the caller`() = runBlocking {
        val dispatcher = RecordingDispatcher()
        val materialized = OcrImageMaterializer.encode(image, dispatcher)

        assertNotNull(materialized)
        assertEquals("png", materialized.data.format)
        assertTrue(dispatcher.dispatches > 0)
    }
}

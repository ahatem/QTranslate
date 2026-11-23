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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Blocking image work must go through the injected dispatcher, never run on the caller. */
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
    fun `blocking image work runs on the injected dispatcher`() {
        val clipboard = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = image
        }
        val file = Files.createTempFile("qtranslate-ocr-image", ".png").toFile()
        try {
            ImageIO.write(image, "png", file)
            val cases = listOf<Pair<String, suspend (CoroutineDispatcher) -> String?>>(
                "decode" to { d ->
                    OcrImageMaterializer.decode(ImageData(pngBytes(), "png", image.width, image.height), d)
                        ?.let { "${it.width}" }
                },
                "readFile" to { d ->
                    OcrImageMaterializer.readFile(file, d)?.let { "${it.image.width} ${it.data.format}" }
                },
                "readClipboard" to { d ->
                    OcrImageMaterializer.readClipboard(clipboard, d)?.let { "${it.image.width} ${it.data.format}" }
                },
                "encode" to { d ->
                    OcrImageMaterializer.encode(image, d)?.let { "${it.image.width} ${it.data.format}" }
                },
            )
            for ((label, run) in cases) {
                val dispatcher = RecordingDispatcher()
                val result = runBlocking { run(dispatcher) }
                assertNotNull(result, "$label result")
                assertTrue(result.startsWith("${image.width}"), "$label width: $result")
                if (label != "decode") assertTrue(result.endsWith("png"), "$label format: $result")
                assertTrue(dispatcher.dispatches > 0, "$label was not dispatched")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `generic clipboard image rasterization happens off EDT`() = runBlocking {
        val conversionThreads = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val generic = object : java.awt.Image() {
            override fun getWidth(observer: java.awt.image.ImageObserver?): Int {
                conversionThreads += javax.swing.SwingUtilities.isEventDispatchThread()
                return image.width
            }
            override fun getHeight(observer: java.awt.image.ImageObserver?): Int {
                conversionThreads += javax.swing.SwingUtilities.isEventDispatchThread()
                return image.height
            }
            override fun getSource(): java.awt.image.ImageProducer = image.source
            override fun getGraphics(): java.awt.Graphics = image.graphics
            override fun getProperty(name: String?, observer: java.awt.image.ImageObserver?): Any = UndefinedProperty
        }
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                assertTrue(javax.swing.SwingUtilities.isEventDispatchThread(), "AWT payload retrieval belongs on EDT")
                return generic
            }
        }
        assertNotNull(OcrImageMaterializer.readClipboard(transferable))
        assertTrue(conversionThreads.isNotEmpty(), "the generic conversion branch must execute")
        assertTrue(conversionThreads.none { it }, "raster allocation/drawing must not run on EDT")
    }
}

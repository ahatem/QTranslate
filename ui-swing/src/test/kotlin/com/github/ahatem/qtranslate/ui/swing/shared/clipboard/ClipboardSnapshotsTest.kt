package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import java.awt.Graphics
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.awt.image.ImageObserver
import java.awt.image.ImageProducer
import java.io.IOException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Snapshot materialization: the clipboard proxy is read eagerly so the restoration payload
 * survives the source application releasing its data.
 */
class ClipboardSnapshotsTest {

    private val customFlavor = DataFlavor("application/x-qtranslate-test")

    @Test
    fun `empty contents classify as empty`() {
        assertIs<ClipboardSnapshotResult.Empty>(ClipboardSnapshots.materialize(null))
        assertIs<ClipboardSnapshotResult.Empty>(ClipboardSnapshots.materialize(MapTransferable(emptyList())))
    }

    @Test
    fun `recognized string flavor retrieval failure classifies as failed`() {
        val broken = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = true

            override fun getTransferData(flavor: DataFlavor): Any =
                throw IOException("owning application released the data")
        }

        val result = assertIs<ClipboardSnapshotResult.Failed>(ClipboardSnapshots.materialize(broken))
        assertIs<IOException>(result.cause)
    }

    @Test
    fun `image that cannot be detached classifies as failed, not lazy`() {
        val sizeless = object : Image() {
            override fun getWidth(observer: ImageObserver?): Int = -1
            override fun getHeight(observer: ImageObserver?): Int = -1
            override fun getProperty(name: String?, observer: ImageObserver?): Any = UndefinedProperty
            override fun getSource(): ImageProducer = throw UnsupportedOperationException()
            override fun getGraphics(): Graphics = throw UnsupportedOperationException()
            override fun flush() = Unit
        }
        val source = MapTransferable(listOf(DataFlavor.imageFlavor to sizeless))

        // Dimensions are unavailable, so no detached copy exists; claiming success with the
        // lazy original would be unsafe.
        assertIs<ClipboardSnapshotResult.Failed>(ClipboardSnapshots.materialize(source))
    }

    @Test
    fun `flavor enumeration failure classifies as failed, never empty`() {
        val unreadable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> =
                throw IllegalStateException("clipboard busy")

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                throw IllegalStateException("clipboard busy")

            override fun getTransferData(flavor: DataFlavor): Any =
                throw UnsupportedFlavorException(flavor)
        }

        val result = assertIs<ClipboardSnapshotResult.Failed>(ClipboardSnapshots.materialize(unreadable))
        assertIs<IllegalStateException>(result.cause)
    }

    @Test
    fun `materializes plain text`() {
        val snapshot = (ClipboardSnapshots.materialize(
            MapTransferable(listOf(DataFlavor.stringFlavor to "hello"))
        ) as ClipboardSnapshotResult.Available).snapshot

        val transferable = snapshot.transferable
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals("hello", transferable.getTransferData(DataFlavor.stringFlavor))
    }

    @Test
    fun `copies an image into a detached buffered image`() {
        val image = BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB)
        val snapshot = (ClipboardSnapshots.materialize(
            MapTransferable(listOf(DataFlavor.imageFlavor to image))
        ) as ClipboardSnapshotResult.Available).snapshot

        val restored = assertIs<BufferedImage>(snapshot.transferable.getTransferData(DataFlavor.imageFlavor))
        assertNotSame(image, restored)
        assertEquals(image.width, restored.width)
        assertEquals(image.height, restored.height)
    }

    @Test
    fun `retains a file list`() {
        val files = listOf(File("one.txt"), File("two.txt"))
        val snapshot = (ClipboardSnapshots.materialize(
            MapTransferable(listOf(DataFlavor.javaFileListFlavor to files))
        ) as ClipboardSnapshotResult.Available).snapshot

        assertTrue(snapshot.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertEquals(files, snapshot.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
    }

    @Test
    fun `keeps unmaterialized flavors through the original transferable`() {
        val source = MapTransferable(listOf(customFlavor to "opaque"))
        val snapshot = (ClipboardSnapshots.materialize(source) as ClipboardSnapshotResult.Available).snapshot

        // Nothing could be read eagerly, so the original transferable is kept as a fallback.
        assertTrue(snapshot.transferable.isDataFlavorSupported(customFlavor))
        assertEquals("opaque", snapshot.transferable.getTransferData(customFlavor))
    }
}

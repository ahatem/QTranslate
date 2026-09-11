package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Snapshot materialization: the clipboard proxy is read eagerly so the restoration payload
 * survives the source application releasing its data.
 */
class ClipboardSnapshotsTest {

    private val customFlavor = DataFlavor("application/x-qtranslate-test")

    @Test
    fun `returns null for empty contents`() {
        assertNull(ClipboardSnapshots.materialize(null))
        assertNull(ClipboardSnapshots.materialize(MapTransferable(emptyList())))
    }

    @Test
    fun `materializes plain text`() {
        val snapshot = ClipboardSnapshots.materialize(
            MapTransferable(listOf(DataFlavor.stringFlavor to "hello"))
        )!!

        val transferable = snapshot.transferable
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals("hello", transferable.getTransferData(DataFlavor.stringFlavor))
    }

    @Test
    fun `copies an image into a detached buffered image`() {
        val image = BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB)
        val snapshot = ClipboardSnapshots.materialize(
            MapTransferable(listOf(DataFlavor.imageFlavor to image))
        )!!

        val restored = assertIs<BufferedImage>(snapshot.transferable.getTransferData(DataFlavor.imageFlavor))
        assertNotSame(image, restored)
        assertEquals(image.width, restored.width)
        assertEquals(image.height, restored.height)
    }

    @Test
    fun `retains a file list`() {
        val files = listOf(File("one.txt"), File("two.txt"))
        val snapshot = ClipboardSnapshots.materialize(
            MapTransferable(listOf(DataFlavor.javaFileListFlavor to files))
        )!!

        assertTrue(snapshot.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertEquals(files, snapshot.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
    }

    @Test
    fun `keeps unmaterialized flavors through the original transferable`() {
        val source = MapTransferable(listOf(customFlavor to "opaque"))
        val snapshot = ClipboardSnapshots.materialize(source)!!

        // Nothing could be read eagerly, so the original transferable is kept as a fallback.
        assertTrue(snapshot.transferable.isDataFlavorSupported(customFlavor))
        assertEquals("opaque", snapshot.transferable.getTransferData(customFlavor))
    }
}

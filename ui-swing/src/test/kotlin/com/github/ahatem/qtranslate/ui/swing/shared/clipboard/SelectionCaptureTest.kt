package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The selection-capture orchestration: the clipboard is never left with internal content, is
 * always restored before the QTranslate action runs, and concurrent hotkeys cannot interleave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionCaptureTest {

    private fun copyThatSelects(
        clipboard: RecordingClipboard,
        monitor: FakeChangeMonitor,
        selected: String,
    ): suspend () -> Unit = {
        monitor.current++
        clipboard.simulateExternalCopy(selected)
    }

    @Test
    fun `capture never publishes an internal marker`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), RecordingLogger())

        capture.capture {}

        assertEquals(listOf("original"), clipboard.published)
        assertTrue(clipboard.published.none { it.startsWith("qtranslate-copy-") })
    }

    @Test
    fun `original plain text is restored after capture`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), RecordingLogger())

        var captured: String? = null
        capture.capture { captured = it }

        assertEquals("selected", captured)
        assertEquals(1, clipboard.restoreCount)
        assertEquals("original", clipboard.text)
    }

    @Test
    fun `selection identical to the existing clipboard text is still captured`() = runTest {
        // The copy leaves the text unchanged, so only the change token can prove it happened.
        val clipboard = RecordingClipboard("same")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { monitor.current++ }, RecordingLogger())

        var captured: String? = null
        capture.capture { captured = it }

        assertEquals("same", captured)
        assertEquals("same", clipboard.text)
    }

    @Test
    fun `image contents are restored after capture`() = runTest {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB)
        val snapshot = ClipboardSnapshots.materialize(
            MapTransferable(listOf(DataFlavor.imageFlavor to image))
        )!!
        val clipboard = RecordingClipboard().apply { setSnapshot(snapshot) }
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { monitor.current++ }, RecordingLogger())

        capture.capture {}

        assertEquals(1, clipboard.restoreCount)
        val restored = clipboard.restored.single().transferable
        assertTrue(restored.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertTrue(restored.getTransferData(DataFlavor.imageFlavor) is BufferedImage)
    }

    @Test
    fun `multi-flavor file list contents are restored after capture`() = runTest {
        val files = listOf(File("a.txt"), File("b.txt"))
        val snapshot = ClipboardSnapshots.materialize(
            MapTransferable(
                listOf(
                    DataFlavor.stringFlavor to "a.txt\nb.txt",
                    DataFlavor.javaFileListFlavor to files
                )
            )
        )!!
        val clipboard = RecordingClipboard().apply { setSnapshot(snapshot) }
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { monitor.current++ }, RecordingLogger())

        capture.capture {}

        val restored = clipboard.restored.single().transferable
        assertTrue(restored.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertTrue(restored.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals(files, restored.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
    }

    @Test
    fun `no clipboard change yields no capture and keeps the original`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, {}, RecordingLogger())

        var captured: String? = null
        capture.capture { captured = it }

        assertEquals("", captured)
        assertEquals("original", clipboard.text)
        assertEquals(1, clipboard.restoreCount)
    }

    @Test
    fun `a delayed clipboard update is still captured within the window`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, {
            delay(150)
            monitor.current++
            clipboard.simulateExternalCopy("delayed")
        }, RecordingLogger())

        var captured: String? = null
        capture.capture { captured = it }

        assertEquals("delayed", captured)
        assertEquals("original", clipboard.text)
    }

    @Test
    fun `capture times out within a bounded window`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, {}, RecordingLogger())

        val started = testScheduler.currentTime
        capture.capture {}
        val elapsed = testScheduler.currentTime - started

        // 80 ms pre-copy delay plus a 600 ms capture window, then it gives up.
        assertEquals(680L, elapsed)
    }

    @Test
    fun `cancellation restores the clipboard and does not dispatch`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, {}, RecordingLogger())

        var dispatched = false
        val job = launch { capture.capture { dispatched = true } }
        advanceTimeBy(200)
        job.cancel()
        job.join()

        assertEquals(1, clipboard.restoreCount)
        assertEquals("original", clipboard.text)
        assertFalse(dispatched)
    }

    @Test
    fun `restoration failure is logged rather than swallowed`() = runTest {
        val clipboard = RecordingClipboard("original").apply {
            restoreFailure = IllegalStateException("clipboard busy")
        }
        val monitor = FakeChangeMonitor()
        val logger = RecordingLogger()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), logger)

        capture.capture {}

        assertTrue(logger.warns.any { it.contains("restore", ignoreCase = true) })
    }

    @Test
    fun `two simultaneous captures are serialized and both restore the clipboard`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        var active = 0
        var maxActive = 0
        val capture = SelectionCapture(clipboard, monitor, {
            active++
            maxActive = maxOf(maxActive, active)
            delay(50)
            monitor.current++
            clipboard.simulateExternalCopy("selected")
            active--
        }, RecordingLogger())

        val callbacks = mutableListOf<String>()
        val first = launch { capture.capture { callbacks += it } }
        val second = launch { capture.capture { callbacks += it } }
        first.join()
        second.join()

        assertEquals(1, maxActive)
        assertEquals(2, clipboard.restoreCount)
        assertEquals(listOf("selected", "selected"), callbacks)
        assertEquals("original", clipboard.text)
    }

    @Test
    fun `a QTranslate copy action does not recursively trigger capture`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), RecordingLogger())

        var callbacks = 0
        capture.capture { callbacks++ }

        // What COPY_TRANSLATION does after a capture: publish the translated text.
        clipboard.restore(ClipboardSnapshot(StringSelection("translated")))
        advanceUntilIdle()

        assertEquals(1, callbacks)
        assertEquals("translated", clipboard.published.last())
    }

    @Test
    fun `a clipboard manager observes no internal marker`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), RecordingLogger())

        capture.capture {}

        assertTrue(clipboard.published.isNotEmpty())
        assertTrue(clipboard.published.none { it.contains("qtranslate-copy-") })
        assertTrue(clipboard.published.none { it.contains("qtranslate") })
    }
}

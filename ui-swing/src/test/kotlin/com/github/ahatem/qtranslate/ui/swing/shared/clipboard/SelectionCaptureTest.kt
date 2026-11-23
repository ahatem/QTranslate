package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    ): suspend () -> Boolean = {
        monitor.current++
        clipboard.simulateExternalCopy(selected)
        true
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

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Success("selected"), captured)
        assertEquals(1, clipboard.restoreCount)
        assertEquals("original", clipboard.text)
    }

    @Test
    fun `selection identical to the existing clipboard text is still captured`() = runTest {
        // The copy leaves the text unchanged, so only the change token can prove it happened.
        val clipboard = RecordingClipboard("same")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { monitor.current++; true }, RecordingLogger())

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Success("same"), captured)
        assertEquals("same", clipboard.text)
    }

    @Test
    fun `image contents are restored after capture`() = runTest {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB)
        val snapshot = (ClipboardSnapshots.materialize(
            MapTransferable(listOf(DataFlavor.imageFlavor to image))
        ) as ClipboardSnapshotResult.Available).snapshot
        val clipboard = RecordingClipboard().apply { setSnapshot(snapshot) }
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { monitor.current++; true }, RecordingLogger())

        capture.capture {}

        assertEquals(1, clipboard.restoreCount)
        val restored = clipboard.restored.single().transferable
        assertTrue(restored.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertTrue(restored.getTransferData(DataFlavor.imageFlavor) is BufferedImage)
    }

    @Test
    fun `multi-flavor file list contents are restored after capture`() = runTest {
        val files = listOf(File("a.txt"), File("b.txt"))
        val snapshot = (ClipboardSnapshots.materialize(
            MapTransferable(
                listOf(
                    DataFlavor.stringFlavor to "a.txt\nb.txt",
                    DataFlavor.javaFileListFlavor to files
                )
            )
        ) as ClipboardSnapshotResult.Available).snapshot
        val clipboard = RecordingClipboard().apply { setSnapshot(snapshot) }
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { monitor.current++; true }, RecordingLogger())

        capture.capture {}

        val restored = clipboard.restored.single().transferable
        assertTrue(restored.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertTrue(restored.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals(files, restored.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
    }

    @Test
    fun `no observed clipboard change is inconclusive, not proven no usable text`() = runTest {
        // Silence from the change monitor is not evidence of an empty selection.
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { true }, RecordingLogger())

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Failed(CaptureFailure.COPY_UNCONFIRMED), captured)
        assertEquals("original", clipboard.text)
        assertEquals(1, clipboard.restoreCount)
    }

    @Test
    fun `a confirmed clipboard change carrying no usable text is reported as such`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, {
            monitor.current++
            clipboard.simulateExternalCopy(null)
            true
        }, RecordingLogger())

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.NoUsableText, captured)
        assertEquals("original", clipboard.text)
        assertEquals(1, clipboard.restoreCount)
    }

    @Test
    fun `copy injection failure is reported as failed, never as no selection`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val logger = RecordingLogger()
        val capture = SelectionCapture(clipboard, monitor, { false }, logger)

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Failed(CaptureFailure.COPY_INJECTION_FAILED), captured)
        assertEquals("original", clipboard.text)
        assertEquals(1, clipboard.restoreCount)
        assertTrue(logger.warns.any { it.contains("injection", ignoreCase = true) })
    }

    @Test
    fun `injection failure does not wait out the capture window`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { false }, RecordingLogger())

        val started = testScheduler.currentTime
        capture.capture {}
        val elapsed = testScheduler.currentTime - started

        assertEquals(0L, elapsed)
    }

    @Test
    fun `a clipboard read failure after a confirmed change is a capture failure, never no usable text`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val logger = RecordingLogger()
        val capture = SelectionCapture(clipboard, monitor, {
            monitor.current++
            clipboard.readTextFailure = IOException("owning application released the data")
            true
        }, logger)

        var captured: CaptureResult? = null
        var callbacks = 0
        capture.capture {
                assertEquals(1, clipboard.restoreCount)
            captured = it
            callbacks++
        }

        assertEquals(CaptureResult.Failed(CaptureFailure.CAPTURE_ERROR), captured)
        assertEquals(1, callbacks)
        assertEquals("original", clipboard.text)
        assertEquals(1, clipboard.restoreCount)
        assertTrue(logger.warns.any { it.contains("read", ignoreCase = true) })
    }

    @Test
    fun `a delayed clipboard update is still captured within the window`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, {
            delay(150)
            monitor.current++
            clipboard.simulateExternalCopy("delayed")
            true
        }, RecordingLogger())

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Success("delayed"), captured)
        assertEquals("original", clipboard.text)
    }

    @Test
    fun `delayed rendering is triggered by reads but only a sequence change counts`() = runTest {
        // The sequence does not move until the data is read; only the verified advance lets text through.
        val clipboard = RecordingClipboard("original")
        val monitor = DelayedRenderingMonitor()
        clipboard.onReadText = { monitor.renderRequested = true }
        val capture = SelectionCapture(clipboard, monitor, {
            monitor.copyAccepted = true
            clipboard.simulateExternalCopy("selected")
            true
        }, RecordingLogger())

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Success("selected"), captured)
        assertEquals("original", clipboard.text)
    }

    @Test
    fun `snapshot failure skips copy and dispatches nothing`() = runTest {
        val clipboard = RecordingClipboard("original").apply {
            snapshotFailure = IllegalStateException("clipboard busy")
        }
        var copyCalls = 0
        val logger = RecordingLogger()
        val capture = SelectionCapture(clipboard, FakeChangeMonitor(), { copyCalls++; true }, logger)

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(0, copyCalls)
        assertEquals(CaptureResult.Failed(CaptureFailure.SNAPSHOT_FAILED), captured)
        assertEquals(0, clipboard.restoreCount)
        assertTrue(clipboard.published.isEmpty())
        assertEquals("original", clipboard.text)
        assertTrue(logger.warns.any { it.contains("snapshot", ignoreCase = true) })
        assertTrue(logger.warns.none { it.contains("original") })
    }

    @Test
    fun `restore retries a busy clipboard and dispatches only after restoring`() = runTest {
        val clipboard = RecordingClipboard("original").apply {
            restoreFailures += IllegalStateException("busy")
        }
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), RecordingLogger())

        var captured: CaptureResult? = null
        capture.capture {
            assertEquals(1, clipboard.restoreCount)
            captured = it
        }

        assertEquals(CaptureResult.Success("selected"), captured)
        assertEquals(2, clipboard.restoreAttempts)
        assertEquals("original", clipboard.text)
    }

    @Test
    fun `permanent restore failure is bounded, logged, and not dispatched`() = runTest {
        val clipboard = RecordingClipboard("original").apply {
            restoreFailure = IllegalStateException("still busy")
        }
        val monitor = FakeChangeMonitor()
        val logger = RecordingLogger()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), logger)

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Failed(CaptureFailure.RESTORE_FAILED), captured)
        assertEquals(3, clipboard.restoreAttempts)
        assertTrue(logger.warns.any { it.contains("restore", ignoreCase = true) })
        assertTrue(logger.warns.none { it.contains("selected") })
        assertTrue(logger.warns.none { it.contains("original") })
    }

    @Test
    fun `non-busy restore failure does not retry`() = runTest {
        val clipboard = RecordingClipboard("original").apply {
            restoreFailure = SecurityException("denied")
        }
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), RecordingLogger())

        capture.capture {}

        assertEquals(1, clipboard.restoreAttempts)
    }

    @Test
    fun `cancellation during restore retries still restores without dispatching`() = runTest {
        val clipboard = RecordingClipboard("original").apply {
            restoreFailures += IllegalStateException("busy")
        }
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(
            clipboard, monitor,
            copyThatSelects(clipboard, monitor, "selected"),
            RecordingLogger(),
            restoreRetryDelayMs = 1_000L
        )

        var dispatched = false
        val job = launch { capture.capture { dispatched = true } }
        advanceTimeBy(200)
        job.cancel()
        job.join()

        assertEquals(1, clipboard.restoreCount)
        assertEquals(2, clipboard.restoreAttempts)
        assertEquals("original", clipboard.text)
        assertFalse(dispatched)
    }

    @Test
    fun `unavailable monitor never dispatches stale clipboard text`() = runTest {
        val clipboard = RecordingClipboard("stale")
        var copyCalls = 0
        val logger = RecordingLogger()
        val capture = SelectionCapture(clipboard, NullChangeMonitor(), {
            copyCalls++
            clipboard.simulateExternalCopy("fresh")
            true
        }, logger)

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Failed(CaptureFailure.MONITOR_UNAVAILABLE), captured)
        assertEquals(0, copyCalls)
        assertEquals("stale", clipboard.text)
        assertTrue(logger.warns.any { it.contains("monitor", ignoreCase = true) })
        // The abort comes before any synthetic Copy, so the clipboard is never written.
        assertEquals(1, clipboard.snapshotCalls)
        assertEquals(0, clipboard.restoreAttempts)
        assertTrue(clipboard.published.isEmpty())
    }

    @Test
    fun `empty clipboard is restored as empty before dispatch`() = runTest {
        val clipboard = RecordingClipboard(null)
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), RecordingLogger())

        var captured: CaptureResult? = null
        var emptyBeforeCallback = false
        capture.capture {
            emptyBeforeCallback = clipboard.text == null
            captured = it
        }

        assertEquals(CaptureResult.Success("selected"), captured)
        assertTrue(emptyBeforeCallback)
        assertNull(clipboard.text)
        assertEquals(1, clipboard.restoreCount)
    }

    @Test
    fun `cancellation after copy restores an originally empty clipboard`() = runTest {
        val clipboard = RecordingClipboard(null)
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, {
            delay(150)
            monitor.current++
            clipboard.simulateExternalCopy("selected")
            true
        }, RecordingLogger())

        var dispatched = false
        val job = launch { capture.capture { dispatched = true } }
        advanceTimeBy(100)
        job.cancel()
        job.join()

        assertEquals(1, clipboard.restoreCount)
        assertNull(clipboard.text)
        assertFalse(dispatched)
    }

    @Test
    fun `transient signature failure does not dispatch stale text`() = runTest {
        val clipboard = RecordingClipboard("stale")
        var signature = 10L
        var reads = 0
        clipboard.signatureProvider = {
            reads++
            // A failed read right after a good mark must read as "no change".
            if (reads == 2) null else signature
        }
        val monitor = SignatureClipboardChangeMonitor(clipboard)
        val capture = SelectionCapture(clipboard, monitor, {
            signature = 11L
            clipboard.simulateExternalCopy("fresh")
            true
        }, RecordingLogger())

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(CaptureResult.Success("fresh"), captured)
        assertEquals("stale", clipboard.text)
    }

    @Test
    fun `unmaterializable string flavor fails closed before copy`() = runTest {
        val broken = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = true

            override fun getTransferData(flavor: DataFlavor): Any =
                throw IOException("owning application released the data")
        }
        val clipboard = RecordingClipboard(null).apply {
            contents = ClipboardSnapshot(broken)
            liveMaterialization = true
        }
        var copyCalls = 0
        val logger = RecordingLogger()
        val capture = SelectionCapture(clipboard, FakeChangeMonitor(), { copyCalls++; true }, logger)

        var captured: CaptureResult? = null
        capture.capture { captured = it }

        assertEquals(0, copyCalls)
        assertEquals(CaptureResult.Failed(CaptureFailure.SNAPSHOT_FAILED), captured)
        assertEquals(0, clipboard.restoreAttempts)
        assertTrue(clipboard.published.isEmpty())
        assertEquals(
            listOf("Clipboard snapshot failed, skipping selection capture: owning application released the data"),
            logger.warns
        )
    }

    @Test
    fun `capture times out within a bounded window`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { true }, RecordingLogger())

        val started = testScheduler.currentTime
        capture.capture {}
        val elapsed = testScheduler.currentTime - started

        assertEquals(600L, elapsed)
    }

    @Test
    fun `cancellation restores the clipboard and does not dispatch`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, { true }, RecordingLogger())

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
            true
        }, RecordingLogger())

        val callbacks = mutableListOf<CaptureResult>()
        val first = launch { capture.capture { callbacks += it } }
        val second = launch { capture.capture { callbacks += it } }
        first.join()
        second.join()

        assertEquals(1, maxActive)
        assertEquals(2, clipboard.restoreCount)
        assertEquals<List<CaptureResult>>(listOf(CaptureResult.Success("selected"), CaptureResult.Success("selected")), callbacks)
        assertEquals("original", clipboard.text)
    }

}

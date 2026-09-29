package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
        // Copy is believed sent (the injector returns true) but the change monitor never
        // reports an advance: this is silence, not evidence, so it must not be reported as
        // CaptureResult.NoUsableText.
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
        // The generation token advances, so something landed on the clipboard after Copy was
        // attempted, but what could be read back was unusable. This does not prove the source
        // application had no selection, and it does not prove this Copy attempt (rather than a
        // racing external actor) caused the advance; see CaptureResult's doc.
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

        // A known-failed injection is terminal immediately: no point polling for a change it
        // cannot have caused.
        assertEquals(0L, elapsed)
    }

    @Test
    fun `a clipboard read failure after a confirmed change is a capture failure, never no usable text`() = runTest {
        // The generation token advances (something landed), but the read that would confirm
        // what it was throws. That is an operational failure, not evidence that nothing usable
        // was there.
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
            // Restore-before-dispatch still holds on the failure path.
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
    fun `an external clipboard write during the capture window is indistinguishable from our own Copy`() = runTest {
        // Known limitation (see SelectionCapture's class doc): the change monitor only proves
        // the clipboard changed after the token was taken, not who changed it. Our own Copy
        // attempt here never touches the clipboard at all; an unrelated actor does, inside the
        // capture window, and the result is reported as an ordinary Success. This test documents
        // the limitation; it does not resolve it.
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, {
            // Our synthesized Copy: injected, but nothing here writes to the clipboard.
            true
        }, RecordingLogger())

        var captured: CaptureResult? = null
        val job = launch { capture.capture { captured = it } }
        delay(50)
        // An unrelated process/clipboard manager writes independently of our Copy attempt.
        monitor.current++
        clipboard.simulateExternalCopy("from another process")
        job.join()

        assertEquals(CaptureResult.Success("from another process"), captured)
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
        // The source app accepts Ctrl+C yet the sequence does not move until the data is
        // requested; each poll read triggers rendering, and only the verified sequence
        // advance lets the text through. The pre-copy "original" reads are discarded.
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
            // Restore-before-dispatch: the clipboard must already be back when this runs.
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

        // Copy succeeded but the original clipboard could not be put back: the
        // callback carries empty instead of the selection, so the action never
        // observes clipboard state the user did not leave there.
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
    }

    @Test
    fun `monitor-unavailable abort performs zero clipboard writes`() = runTest {
        val clipboard = RecordingClipboard("stale")
        val capture = SelectionCapture(clipboard, NullChangeMonitor(), {
            clipboard.simulateExternalCopy("fresh")
            true
        }, RecordingLogger())

        capture.capture {}

        // The abort happens before any synthetic Copy, so there is nothing to
        // restore and no reason to touch the clipboard: managers observe nothing.
        assertEquals(1, clipboard.snapshotCalls)
        assertEquals(0, clipboard.restoreAttempts)
        assertTrue(clipboard.published.isEmpty())
        assertEquals("stale", clipboard.text)
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
            // The first poll read fails right after a good mark; the failure must read
            // as "no change", not as a confirmed copy of whatever is on the clipboard.
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
        // The log carries only the failure message: there were no readable clipboard
        // contents to leak, and none are attached.
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

        // No settle delay of its own anymore: a 600 ms capture window, then it gives up.
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

    @Test
    fun `a QTranslate copy action does not recursively trigger capture`() = runTest {
        val clipboard = RecordingClipboard("original")
        val monitor = FakeChangeMonitor()
        val capture = SelectionCapture(clipboard, monitor, copyThatSelects(clipboard, monitor, "selected"), RecordingLogger())

        var callbacks = 0
        capture.capture { callbacks++ }

        // What COPY_TRANSLATION does after a capture: publish the translated text.
        clipboard.restore(ClipboardSnapshotResult.Available(ClipboardSnapshot(StringSelection("translated"))))
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

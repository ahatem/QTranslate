package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File

/**
 * A self-contained copy of the system clipboard.
 *
 * The platform clipboard returns lazy proxies whose data can disappear once the owning
 * application is done with it, so the payload is read eagerly at capture time and kept
 * alive until it is published back.
 */
class ClipboardSnapshot(val transferable: Transferable)

/**
 * The outcome of reading the clipboard for later restoration.
 *
 * The three states are deliberately distinct: an unreadable or busy clipboard ([Failed])
 * must never be confused with an empty one ([Empty]), because the capture flow is only
 * allowed to overwrite clipboard state it knows how to restore.
 */
sealed interface ClipboardSnapshotResult {

    /** The clipboard held readable contents; [snapshot] restores them. */
    data class Available(val snapshot: ClipboardSnapshot) : ClipboardSnapshotResult

    /** The clipboard was readable and held nothing restorable. */
    data object Empty : ClipboardSnapshotResult

    /** The clipboard could not be read (busy, unavailable); nothing was captured. */
    data class Failed(val cause: Throwable?) : ClipboardSnapshotResult
}

/** Abstract view of the system clipboard so capture logic can be tested without AWT. */
interface SystemClipboard {

    /** Eagerly copies the current contents for later restoration. Never throws. */
    fun snapshot(): ClipboardSnapshotResult

    /** Current plain-text contents, or null when the clipboard holds no text. */
    fun readText(): String?

    /** Cheap signature of the current contents, used by the fallback change monitor. */
    fun signature(): Long?

    /**
     * Publishes a previously snapshotted state back to the clipboard. [ClipboardSnapshotResult.Empty]
     * clears the clipboard back to empty; [ClipboardSnapshotResult.Failed] is never a valid
     * argument and fails fast, since the capture flow must abort before Copy instead. May throw.
     */
    fun restore(state: ClipboardSnapshotResult)
}

/** A transferable offering no data, used to restore a genuinely empty clipboard. */
object EmptyTransferable : Transferable {

    override fun getTransferDataFlavors(): Array<DataFlavor> = emptyArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = false

    override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
}

/** Materializes [Transferable] contents into an eager snapshot. */
object ClipboardSnapshots {

    /**
     * Null contents classify as [ClipboardSnapshotResult.Empty]. So does a transferable whose
     * flavor enumeration succeeds and reports zero flavors: there is observably nothing to
     * preserve. Any failure while enumerating classifies as [ClipboardSnapshotResult.Failed],
     * and so does a retrieval failure for a recognized flavor (text, image, file list): the
     * snapshot cannot prove the data was captured, so it must not claim success. Genuinely
     * unsupported or custom flavors stay best effort and never fail the snapshot on their own.
     */
    fun materialize(contents: Transferable?): ClipboardSnapshotResult {
        if (contents == null) return ClipboardSnapshotResult.Empty
        val flavors = runCatching { contents.transferDataFlavors }
            .getOrElse { return ClipboardSnapshotResult.Failed(it) }
        if (flavors.isEmpty()) return ClipboardSnapshotResult.Empty

        val captured = ArrayList<Pair<DataFlavor, Any>>()
        for (flavor in flavors) {
            when (val outcome = materializeFlavor(contents, flavor)) {
                is FlavorOutcome.Materialized -> captured += flavor to outcome.value
                is FlavorOutcome.Unsupported -> Unit
                is FlavorOutcome.Failed -> return ClipboardSnapshotResult.Failed(outcome.cause)
            }
        }
        // Only unsupported flavors were present: keep the original transferable as a best effort.
        if (captured.isEmpty()) return ClipboardSnapshotResult.Available(ClipboardSnapshot(contents))
        return ClipboardSnapshotResult.Available(ClipboardSnapshot(SnapshotTransferable(captured, contents)))
    }

    private sealed interface FlavorOutcome {
        data class Materialized(val value: Any) : FlavorOutcome
        data object Unsupported : FlavorOutcome
        data class Failed(val cause: Throwable) : FlavorOutcome
    }

    private fun materializeFlavor(contents: Transferable, flavor: DataFlavor): FlavorOutcome {
        if (flavor != DataFlavor.stringFlavor &&
            flavor != DataFlavor.imageFlavor &&
            flavor != DataFlavor.javaFileListFlavor
        ) {
            return FlavorOutcome.Unsupported
        }
        return runCatching {
            FlavorOutcome.Materialized(
                when (flavor) {
                    DataFlavor.stringFlavor -> contents.getTransferData(DataFlavor.stringFlavor) as String
                    DataFlavor.imageFlavor -> copyImage(contents.getTransferData(DataFlavor.imageFlavor) as Image)
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        (contents.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).toList()
                    }
                }
            )
        }.getOrElse { FlavorOutcome.Failed(it) }
    }

    /**
     * Detaches the image from the source so it survives after the source app loses ownership.
     *
     * Images whose dimensions are unavailable cannot be detached into a buffered copy; handing
     * back the lazy original would claim an eager snapshot that was never taken, so this fails
     * and the snapshot classifies as failed instead.
     */
    private fun copyImage(image: Image): Image {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("Cannot detach clipboard image with unavailable dimensions")
        }
        val copy = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = copy.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return copy
    }
}

/** Serves eagerly captured data first and falls back to the original transferable otherwise. */
private class SnapshotTransferable(
    private val captured: List<Pair<DataFlavor, Any>>,
    private val fallback: Transferable,
) : Transferable {

    override fun getTransferDataFlavors(): Array<DataFlavor> {
        val flavors = LinkedHashSet<DataFlavor>()
        captured.forEach { flavors += it.first }
        runCatching { fallback.transferDataFlavors }.getOrNull()?.let { flavors.addAll(it) }
        return flavors.toTypedArray()
    }

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        captured.any { it.first == flavor } ||
            runCatching { fallback.isDataFlavorSupported(flavor) }.getOrDefault(false)

    override fun getTransferData(flavor: DataFlavor): Any =
        captured.firstOrNull { it.first == flavor }?.second ?: fallback.getTransferData(flavor)
}

/** Real clipboard backed by AWT. This is the only place that touches [Toolkit]. */
class AwtSystemClipboard : SystemClipboard {

    override fun snapshot(): ClipboardSnapshotResult = runCatching {
        val contents = systemClipboard.getContents(null)
            ?: return@runCatching ClipboardSnapshotResult.Empty
        ClipboardSnapshots.materialize(contents)
    }.getOrElse { ClipboardSnapshotResult.Failed(it) }

    override fun readText(): String? = runCatching {
        val clipboard = systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return@runCatching null
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun signature(): Long? = runCatching {
        val clipboard = systemClipboard
        val contents = clipboard.getContents(null) ?: return@runCatching 0L
        val flavors = contents.transferDataFlavors.joinToString(",") {
            "${it.mimeType}:${it.representationClass?.name}"
        }
        val text = if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            runCatching { clipboard.getData(DataFlavor.stringFlavor).toString() }.getOrNull()
        } else {
            null
        }
        "$flavors|$text".hashCode().toLong()
    }.getOrNull()

    override fun restore(state: ClipboardSnapshotResult) {
        when (state) {
            is ClipboardSnapshotResult.Available -> systemClipboard.setContents(state.snapshot.transferable, null)
            // An empty original clipboard is an explicit state to restore, not the
            // absence of work: without this Copy would leave the selection behind.
            is ClipboardSnapshotResult.Empty -> systemClipboard.setContents(EmptyTransferable, null)
            is ClipboardSnapshotResult.Failed ->
                throw IllegalStateException("Cannot restore a failed clipboard snapshot", state.cause)
        }
    }

    private val systemClipboard get() = Toolkit.getDefaultToolkit().systemClipboard
}

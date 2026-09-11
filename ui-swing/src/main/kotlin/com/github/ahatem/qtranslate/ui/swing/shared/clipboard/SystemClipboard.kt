package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
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

/** Abstract view of the system clipboard so capture logic can be tested without AWT. */
interface SystemClipboard {

    /** Eagerly copies the current contents. Null when the clipboard is empty or unreadable. */
    fun snapshot(): ClipboardSnapshot?

    /** Current plain-text contents, or null when the clipboard holds no text. */
    fun readText(): String?

    /** Cheap signature of the current contents, used by the fallback change monitor. */
    fun signature(): Long?

    /** Publishes [snapshot] back to the clipboard. May throw. */
    fun restore(snapshot: ClipboardSnapshot)
}

/** Materializes [Transferable] contents into an eager snapshot. */
object ClipboardSnapshots {

    fun materialize(contents: Transferable?): ClipboardSnapshot? {
        if (contents == null) return null
        val flavors = runCatching { contents.transferDataFlavors }.getOrNull() ?: return null
        if (flavors.isEmpty()) return null

        val captured = ArrayList<Pair<DataFlavor, Any>>()
        for (flavor in flavors) {
            materializeFlavor(contents, flavor)?.let { captured += flavor to it }
        }
        // Nothing could be read eagerly: keep the original transferable as a best effort.
        if (captured.isEmpty()) return ClipboardSnapshot(contents)
        return ClipboardSnapshot(SnapshotTransferable(captured, contents))
    }

    private fun materializeFlavor(contents: Transferable, flavor: DataFlavor): Any? = runCatching {
        when (flavor) {
            DataFlavor.stringFlavor -> contents.getTransferData(DataFlavor.stringFlavor) as String
            DataFlavor.imageFlavor -> copyImage(contents.getTransferData(DataFlavor.imageFlavor) as Image)
            DataFlavor.javaFileListFlavor -> {
                @Suppress("UNCHECKED_CAST")
                (contents.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).toList()
            }
            else -> null
        }
    }.getOrNull()

    /** Detaches the image from the source so it survives after the source app loses ownership. */
    private fun copyImage(image: Image): Image {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return image
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

    override fun snapshot(): ClipboardSnapshot? =
        runCatching { ClipboardSnapshots.materialize(systemClipboard.getContents(null)) }.getOrNull()

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

    override fun restore(snapshot: ClipboardSnapshot) {
        systemClipboard.setContents(snapshot.transferable, null)
    }

    private val systemClipboard get() = Toolkit.getDefaultToolkit().systemClipboard
}

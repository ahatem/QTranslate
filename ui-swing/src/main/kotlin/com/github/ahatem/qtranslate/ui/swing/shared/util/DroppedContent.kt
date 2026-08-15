package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.core.document.DocumentFormat
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * What a dropped or pasted transfer turned out to be.
 *
 * [None] covers plain text as well as anything unrecognised, because text is not this classifier's
 * business: a text drag belongs to whichever editor it landed on, and saying so here would take it
 * away from them.
 */
sealed interface DroppedContent {
    data class Picture(val image: BufferedImage) : DroppedContent
    data class Document(val file: File) : DroppedContent
    data object None : DroppedContent
}

/**
 * Decides what a [Transferable] holds.
 *
 * This exists because the answer used to be worked out in two places — the input pane and the
 * frame — which disagreed. The pane claimed every file list, including documents it could not
 * open, so a `.docx` dropped on it was accepted and then silently discarded instead of reaching
 * the frame's handler. Document drop therefore worked only on window chrome, which is the part of
 * the window nobody aims at.
 *
 * One classifier, one answer, and the two callers differ only in what they do with it.
 */
object DroppedContentClassifier {

    private val IMAGE_EXTENSIONS =
        setOf("png", "jpg", "jpeg", "bmp", "gif", "tiff", "tif", "webp")

    /**
     * Classifies [transferable].
     *
     * Images are checked before files so a drag carrying both — which some applications offer —
     * is treated as the picture it appears to be. Among files, a picture still wins over a
     * document for the same reason.
     */
    fun classify(transferable: Transferable): DroppedContent {
        imageFrom(transferable)?.let { return DroppedContent.Picture(it) }

        val files = filesFrom(transferable) ?: return DroppedContent.None

        files.firstOrNull { it.isImageFile() }
            ?.let { file -> readImage(file)?.let { return DroppedContent.Picture(it) } }

        files.firstOrNull { DocumentFormat.from(it) != null }
            ?.let { return DroppedContent.Document(it) }

        return DroppedContent.None
    }

    /**
     * Whether [transferable] is worth accepting, judged **only** by the flavours it advertises.
     *
     * It deliberately does not look at the contents, because it cannot: during a drag the data is
     * not readable yet — Windows hands it over only once the drop has been accepted, and asking
     * early throws. An earlier version inspected the file list here to check the extensions, and
     * the result was that every file drag was silently refused, since the read failed every time
     * and a failed read looked exactly like "nothing here I want". Pasting the same file worked,
     * because clipboard data has no such restriction.
     *
     * So any file list is accepted and [classify] decides for real after the drop. A file this
     * application has no use for simply does nothing, which is the correct outcome anyway.
     */
    fun canAccept(transferable: Transferable): Boolean =
        transferable.isDataFlavorSupported(DataFlavor.imageFlavor) ||
            transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

    private fun imageFrom(transferable: Transferable): BufferedImage? {
        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        val image = runCatching {
            transferable.getTransferData(DataFlavor.imageFlavor) as? Image
        }.getOrNull() ?: return null
        return image.toBuffered()
    }

    @Suppress("UNCHECKED_CAST")
    private fun filesFrom(transferable: Transferable): List<File>? {
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
        return runCatching {
            transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
        }.getOrNull()
    }

    private fun readImage(file: File): BufferedImage? = runCatching { ImageIO.read(file) }.getOrNull()

    private fun File.isImageFile(): Boolean = extension.lowercase() in IMAGE_EXTENSIONS
}

/** Converts to [BufferedImage], which is what the OCR path needs, without copying when possible. */
fun Image.toBuffered(): BufferedImage {
    if (this is BufferedImage) return this
    val buffered = BufferedImage(
        getWidth(null).coerceAtLeast(1),
        getHeight(null).coerceAtLeast(1),
        BufferedImage.TYPE_INT_ARGB
    )
    val graphics = buffered.createGraphics()
    graphics.drawImage(this, 0, 0, null)
    graphics.dispose()
    return buffered
}

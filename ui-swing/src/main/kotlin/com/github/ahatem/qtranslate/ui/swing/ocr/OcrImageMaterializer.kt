package com.github.ahatem.qtranslate.ui.swing.ocr

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.ui.swing.shared.util.ClipboardImageCandidate
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContentClassifier
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** A decoded image together with the bytes the OCR host consumes. */
internal data class MaterializedImage(
    val image: BufferedImage,
    val data: ImageData,
)

/**
 * Blocking image work for the OCR Workspace.
 *
 * Clipboard access itself stays on the EDT because AWT requires it there. Classification, file
 * reads, image decoding and PNG encoding are all dispatched away from Swing so a large or slow
 * file cannot freeze the window.
 */
internal object OcrImageMaterializer {

    suspend fun decode(
        image: ImageData,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): BufferedImage? = withContext(io) {
        runCatching { ImageIO.read(image.bytes.inputStream()) }.getOrNull()
    }

    suspend fun encode(
        image: BufferedImage,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): MaterializedImage? = withContext(io) {
        runCatching { MaterializedImage(image, image.toImageData("png")) }.getOrNull()
    }

    suspend fun readFile(
        file: File,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): MaterializedImage? = withContext(io) {
        runCatching {
            ImageIO.read(file)?.let { image -> MaterializedImage(image, image.toImageData("png")) }
        }.getOrNull()
    }

    suspend fun readClipboard(
        transferable: Transferable,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): MaterializedImage? {
        // The transferable's image/file metadata belongs to AWT and is read on the EDT. The
        // potentially blocking file read and PNG encoding happen on the IO dispatcher.
        val candidate = withContext(Dispatchers.Swing) {
            DroppedContentClassifier.imageCandidate(transferable)
        }
        val picture = when (candidate) {
            is ClipboardImageCandidate.Picture -> candidate.image
            is ClipboardImageCandidate.ImageFile -> withContext(io) {
                DroppedContentClassifier.readImageFile(candidate.file)
            }
            ClipboardImageCandidate.None -> null
        } ?: return null
        return withContext(io) {
            runCatching {
                MaterializedImage(picture, picture.toImageData("png"))
            }.getOrNull()
        }
    }
}

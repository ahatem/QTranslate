package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/** Image bytes in a form a platform engine can consume, after validation (and any resize). */
internal data class PreparedImage(
    val bytes: ByteArray,
    val format: String,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedImage) return false
        return bytes.contentEquals(other.bytes) && format == other.format &&
            width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * Validates image bytes and resizes when an engine imposes a maximum dimension, preserving aspect
 * ratio. A corrupt or unsupported image is reported as [ServiceError.InvalidInputError] here, before
 * any process is started.
 *
 * Decoding and resizing are separate steps because Windows only learns its limit by asking the engine.
 */
internal object OcrImage {

    /** Validates [image] and returns its decoded pixels. */
    fun decode(image: ImageData): Result<BufferedImage, ServiceError> {
        if (image.bytes.isEmpty()) {
            return Err(ServiceError.InvalidInputError("The image contains no data."))
        }
        if (image.width <= 0 || image.height <= 0) {
            return Err(
                ServiceError.InvalidInputError(
                    "The image dimensions must be positive, but were ${image.width}x${image.height}."
                )
            )
        }
        val decoded = decodeBytes(image.bytes)
            ?: return Err(
                ServiceError.InvalidInputError(
                    "The image could not be decoded; it is corrupt or in an unsupported format."
                )
            )
        return Ok(decoded)
    }

    /** Decodes and, if needed, resizes. [maxDimension] is the engine's limit, or null for none. */
    fun prepare(image: ImageData, maxDimension: Int?): Result<PreparedImage, ServiceError> =
        decode(image).fold(
            success = { decoded -> prepare(image, decoded, maxDimension) },
            failure = { Err(it) },
        )

    /** The resize step, for an engine whose limit is only known after a round trip to the platform. */
    fun prepare(
        image: ImageData,
        decoded: BufferedImage,
        maxDimension: Int?,
    ): Result<PreparedImage, ServiceError> {
        val resized = maxDimension?.let { scaleToFit(decoded, it) }
        if (resized == null) {
            val format = image.format.trim().lowercase().ifBlank { DEFAULT_FORMAT }
            return Ok(PreparedImage(image.bytes, format, decoded.width, decoded.height))
        }

        val encoded = encodePng(resized)
            ?: return Err(ServiceError.InvalidInputError("The image could not be re-encoded after resizing."))
        return Ok(PreparedImage(encoded, DEFAULT_FORMAT, resized.width, resized.height))
    }

    private fun decodeBytes(bytes: ByteArray): BufferedImage? = try {
        ImageIO.read(ByteArrayInputStream(bytes))
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Scales [source] down so its longest side is at most [max], preserving aspect ratio, and
     * returns null when it already fits. Dimensions are rounded, so the result is deterministic.
     */
    private fun scaleToFit(source: BufferedImage, max: Int): BufferedImage? {
        if (source.width <= max && source.height <= max) return null

        val ratio = minOf(max.toDouble() / source.width, max.toDouble() / source.height)
        val width = maxOf(1, Math.round(source.width * ratio).toInt())
        val height = maxOf(1, Math.round(source.height * ratio).toInt())

        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, Color.WHITE, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodePng(image: BufferedImage): ByteArray? = try {
        val out = ByteArrayOutputStream()
        if (ImageIO.write(image, DEFAULT_FORMAT, out)) out.toByteArray() else null
    } catch (_: IOException) {
        null
    }

    private const val DEFAULT_FORMAT = "png"
}

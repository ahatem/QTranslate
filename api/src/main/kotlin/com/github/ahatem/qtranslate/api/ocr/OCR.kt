package com.github.ahatem.qtranslate.api.ocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that performs Optical Character Recognition (OCR) on images,
 * extracting any text content found within them.
 *
 * Language support is declared via [Service.supportedLanguages]. Providing a language
 * hint in [OCRRequest.language] can improve accuracy for services that support it,
 * but is not required if [LanguageCode.AUTO] is used.
 */
interface OCR : Service {

    /**
     * Extracts text from the image described by [request].
     *
     * @param request The OCR parameters, including the image data and an optional language hint.
     * @return `Ok` with the [OCRResponse] on success, or an `Err` with a [ServiceError].
     *         Returns [ServiceError.InvalidInputError] for corrupted or unsupported image data.
     */
    suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError>
}

/**
 * Raw image data to be processed by an [OCR] service.
 *
 * @param bytes  The raw bytes of the image file.
 * @param format The image format identifier (e.g. `"png"`, `"jpeg"`, `"webp"`).
 * @param width  The image width in pixels.
 * @param height The image height in pixels.
 */
data class ImageData(
    val bytes: ByteArray,
    val format: String,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageData
        return bytes.contentEquals(other.bytes)
                && format == other.format
                && width == other.width
                && height == other.height
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
 * Parameters for an OCR operation.
 *
 * @param image    The image to process.
 * @param language An optional language hint to improve recognition accuracy.
 *                 Defaults to [LanguageCode.AUTO] (the service detects the language).
 *                 Ignored by services that do not support language hints.
 */
data class OCRRequest(
    val image: ImageData,
    val language: LanguageCode = LanguageCode.AUTO
)

/**
 * The result of a successful OCR operation.
 *
 * @param text              The text extracted from the image. May be empty if no
 *                          text was found, but will never be null on success.
 * @param confidence        An optional confidence score in the range `[0.0, 1.0]`,
 *                          where `1.0` represents perfect confidence. `null` if the
 *                          service does not provide a confidence score.
 * @param detectedLanguage  The language detected in the image text, if the service
 *                          supports language detection. `null` otherwise.
 */
data class OCRResponse(
    val text: String,
    val confidence: Float? = null,
    val detectedLanguage: LanguageCode? = null
) {
    init {
        require(confidence == null || confidence in 0f..1f) {
            "OCR confidence score must be in [0.0, 1.0], but was $confidence."
        }
    }
}
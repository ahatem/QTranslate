package com.github.ahatem.qtranslate.api.ocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.language.LanguageSupport
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result


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
        if (!bytes.contentEquals(other.bytes)) return false
        if (format != other.format) return false
        if (width != other.width) return false
        if (height != other.height) return false
        return true
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
 * A service that performs Optical Character Recognition on images.
 */
interface OCR : Service, LanguageSupport {
    suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError>
}

data class OCRRequest(
    val image: ImageData,
    val language: LanguageCode = LanguageCode.AUTO // Hint for the service
)

data class OCRResponse(
    val text: String,
    val confidence: Float? = null, // Value between 0.0 and 1.0
    val detectedLanguage: LanguageCode? = null
)
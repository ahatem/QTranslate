package com.github.ahatem.qtranslate.api.ocr

import com.github.ahatem.qtranslate.api.LanguageCode
import com.github.ahatem.qtranslate.api.LanguageSupport
import com.github.ahatem.qtranslate.api.Service
import com.github.ahatem.qtranslate.api.ServiceError
import com.github.michaelbull.result.Result
import java.awt.image.BufferedImage

/**
 * A service that performs Optical Character Recognition on images.
 */
interface OCR : Service, LanguageSupport {
    suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError>
}

data class OCRRequest(
    val image: BufferedImage,
    val language: LanguageCode = LanguageCode.Companion.AUTO // Hint for the service
)

data class OCRResponse(
    val text: String,
    val confidence: Float? = null, // Value between 0.0 and 1.0
    val detectedLanguage: LanguageCode? = null
)
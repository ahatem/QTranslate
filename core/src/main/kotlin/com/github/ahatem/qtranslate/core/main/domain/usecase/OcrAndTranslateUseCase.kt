package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull

class OcrAndTranslateUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("OcrAndTranslateUseCase")

    private companion object {
        const val OCR_TIMEOUT_MS = 30_000L
    }

    /**
     * Performs OCR on [image] and returns the extracted text.
     *
     * Returns an empty string on any failure. The [MainStore] that calls this
     * should update [MainState.inputText] with the result and trigger translation
     * only if the returned string is non-blank.
     */
    suspend operator fun invoke(
        image: ImageData,
        currentState: MainState,
        onStatusUpdate: suspend (message: String, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String {
        val ocrService = activeServiceManager.getActiveService<OCR>(ServiceType.OCR)
        if (ocrService == null) {
            logger.warn("No OCR service available")
            onStatusUpdate("No OCR (Text Recognition) service is active.", NotificationType.ERROR, true)
            return ""
        }

        logger.info("Starting OCR with '${ocrService.name}'")
        onStatusUpdate("Recognizing text from image...", NotificationType.INFO, false)

        val request = OCRRequest(image, language = currentState.sourceLanguage)
        logger.debug("OCR request: language=${currentState.sourceLanguage}")

        val result = withTimeoutOrNull(OCR_TIMEOUT_MS) {
            ocrService.extractText(request)
        }

        if (result == null) {
            logger.error("OCR timed out after ${OCR_TIMEOUT_MS}ms")
            onStatusUpdate(
                "OCR timed out. Please try again with a smaller image.",
                NotificationType.ERROR,
                true
            )
            return ""
        }

        return result.fold(
            success = { response ->
                if (response.text.isBlank()) {
                    logger.warn("No text detected in image")
                    onStatusUpdate("No text was found in the captured image.", NotificationType.WARNING, true)
                    ""
                } else {
                    logger.info("OCR successful: detected ${response.text.length} characters")
                    onStatusUpdate("Text recognized successfully!", NotificationType.SUCCESS, true)
                    response.text
                }
            },
            failure = { error ->
                logger.error("OCR failed: ${error.message}", error.cause)
                onStatusUpdate("Text recognition failed: ${error.message?.lines()?.firstOrNull()?.take(120)}", NotificationType.ERROR, true)
                ""
            }
        )
    }
}
package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess


class OcrAndTranslateUseCase(
    private val activeServiceManager: ActiveServiceManager,
) {

    suspend operator fun invoke(
        image: ImageData,
        currentState: MainState,
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit
    ) {
        onStatusUpdate("Recognizing text from image...", NotificationType.INFO)

        val ocrService = activeServiceManager.getActiveService<OCR>(ServiceType.OCR, currentState)
        if (ocrService == null) {
            onStatusUpdate("No OCR (Text Recognition) service is active.", NotificationType.ERROR)
            return
        }

        val request = OCRRequest(image, language = currentState.sourceLanguage)
        ocrService.extractText(request)
            .onSuccess { response ->
                val extractedText = response.text
                if (extractedText.isNotBlank()) {
                    onStatusUpdate("Text recognized. Translating...", NotificationType.INFO)

                    TODO("Implement translation")

                } else {
                    onStatusUpdate("No text was found in the captured image.", NotificationType.WARNING)
                }
            }
            .onFailure { error ->
                onStatusUpdate("Text recognition failed: ${error.message}", NotificationType.ERROR)
            }
    }
}
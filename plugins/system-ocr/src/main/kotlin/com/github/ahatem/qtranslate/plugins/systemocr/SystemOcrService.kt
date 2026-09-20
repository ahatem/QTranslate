package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.plugins.systemocr.backend.SystemOcrBackend
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.map

/**
 * The OCR service QTranslate sees, delegating recognition to whichever [SystemOcrBackend] the
 * platform provides. Text the engine found is returned as-is, including an empty result; engine and
 * environment failures are errors.
 */
internal class SystemOcrService(
    private val backend: SystemOcrBackend,
    private val logger: Logger,
) : OCR {

    override val key: String = "system-ocr"

    override val name: String = "System OCR (Offline)"

    override val version: String = "1.0.0"

    override val iconPath: String = "assets/system-ocr-icon.svg"

    override val metadata: ServiceMetadata = ServiceMetadata(
        requiresConfiguration = false,
        isFree = true,
        notes = DisplayText.literal(
            "Recognition runs entirely on this device using ${backend.displayName}. " +
                "Images are never uploaded."
        ),
    )

    /**
     * Which languages can be recognized depends on the machine, so the host asks. `LanguageCode.AUTO`
     * means "detect the image language", so it is offered only for a backend that detects.
     */
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        backend.supportedLanguages().map { supported ->
            if (supported.detectsLanguage) supported.languages + LanguageCode.AUTO else supported.languages
        }

    override suspend fun validate(): Result<Unit, ServiceError> = backend.validate()

    override suspend fun extractText(
        request: OCRRequest,
    ): Result<OCRResponse, ServiceError> = coroutineBinding {
        val startedAt = System.nanoTime()
        val text = backend.recognize(request.image, request.language).bind()
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        // Dimensions, language and engine are safe to log; the recognized text and image are not.
        logger.info(
            "System OCR (${backend.displayName}) recognized ${request.image.width}x${request.image.height} " +
                "as '${request.language.tag}' in ${elapsedMillis}ms (${text.length} characters)"
        )

        OCRResponse(text = text)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

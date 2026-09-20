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
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.fold
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
        val language = languageToRecognize(request.language).bind()

        val startedAt = System.nanoTime()
        val text = backend.recognize(request.image, language).bind()
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        // Dimensions, language and engine are safe to log; the recognized text and image are not.
        logger.info(
            "System OCR (${backend.displayName}) recognized ${request.image.width}x${request.image.height} " +
                "as '${language.tag}' in ${elapsedMillis}ms (${text.length} characters)"
        )

        OCRResponse(text = text)
    }

    /**
     * The language to recognize with. `AUTO` asks for language detection, so it is passed on only to
     * a backend that detects. Any other backend gets a concrete installed language instead, which
     * keeps the default AUTO workflow working without pretending the engine detected anything.
     */
    private suspend fun languageToRecognize(requested: LanguageCode): Result<LanguageCode, ServiceError> =
        if (requested != LanguageCode.AUTO) {
            Ok(requested)
        } else {
            backend.supportedLanguages().fold(
                success = { supported ->
                    when {
                        supported.detectsLanguage -> Ok(LanguageCode.AUTO)
                        else -> AutoLanguageResolver
                            .resolve(supported.languages, AutoLanguageResolver.platformPreferredTags())
                            ?.let { Ok(it) }
                            ?: Err(
                                ServiceError.UnsupportedLanguageError(
                                    LanguageCode.AUTO,
                                    NO_INSTALLED_LANGUAGE_MESSAGE,
                                )
                            )
                    }
                },
                failure = { Err(it) },
            )
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NO_INSTALLED_LANGUAGE_MESSAGE =
            "No OCR language is installed, so automatic selection has nothing to choose from. " +
                "Choose a specific language, or install language data for this system."
    }
}

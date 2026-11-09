package com.github.ahatem.qtranslate.core.ocr

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.api.ocr.StructuredOCR
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.prepareInputText
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.util.shortSummary
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A successful OCR extraction, including optional geometry and the provider that produced it. */
data class OcrExtractionSuccess(
    val text: String,
    val serviceId: String,
    val serviceName: String,
    val detectedLanguage: LanguageCode? = null,
    val confidence: Float? = null,
    val regions: List<OCRTextRegion> = emptyList(),
)

/** Everything an OCR request can end in. Typed so every caller can recover deliberately. */
sealed interface OcrExtractionOutcome {
    data class Success(val result: OcrExtractionSuccess) : OcrExtractionOutcome
    data object NoService : OcrExtractionOutcome
    data object NoText : OcrExtractionOutcome
    data object Timeout : OcrExtractionOutcome
    data class Failure(
        val summary: String,
        /** Safe diagnostic type; never the provider's message. */
        val errorType: String = "ServiceError",
    ) : OcrExtractionOutcome
}

/**
 * The single host-side OCR request implementation.
 *
 * It resolves a provider (the active OCR service, or an explicitly chosen one), applies the same
 * timeout and failure mapping everywhere, and asks for structured geometry when the provider
 * opts in through [StructuredOCR]. It owns no state: callers provide request ownership.
 */
class OcrExtractionUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory,
) {
    private val logger: Logger = loggerFactory.getLogger("OcrExtractionUseCase")

    private companion object {
        const val OCR_TIMEOUT_MS = 30_000L
    }

    suspend fun extract(
        image: ImageData,
        language: LanguageCode,
        serviceId: String? = null,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit = { _, _, _ -> },
    ): OcrExtractionOutcome {
        val resolved = if (serviceId == null) {
            activeServiceManager.getActive<OCR>(ServiceRole.OCR)
        } else {
            activeServiceManager.resolve(serviceId, ServiceRole.OCR)
        }

        val service = resolved?.service
        if (resolved == null || service == null) {
            logger.warn("No OCR service available")
            onStatusUpdate(StatusCode.NoOcrServiceActive, NotificationType.ERROR, true)
            return OcrExtractionOutcome.NoService
        }

        logger.info("Starting OCR with '${service.name}'")
        onStatusUpdate(StatusCode.RecognizingText, NotificationType.INFO, false)

        val request = OCRRequest(image, language = language)
        logger.debug("OCR request: language=$language")

        val structured = service as? StructuredOCR
        val outcome = try {
            withTimeoutOrNull(OCR_TIMEOUT_MS) {
                if (structured != null) {
                    structured.extractStructuredText(request).fold(
                        success = { response ->
                            OcrExtractionOutcome.Success(
                                OcrExtractionSuccess(
                                    text = response.text,
                                    serviceId = resolved.id,
                                    serviceName = service.name,
                                    detectedLanguage = response.detectedLanguage,
                                    confidence = response.confidence,
                                    regions = response.regions,
                                )
                            )
                        },
                        failure = { error ->
                            OcrExtractionOutcome.Failure(
                                summary = error.shortSummary(),
                                errorType = error::class.simpleName ?: "ServiceError",
                            )
                        },
                    )
                } else {
                    service.extractText(request).fold(
                        success = { response ->
                            OcrExtractionOutcome.Success(
                                OcrExtractionSuccess(
                                    text = response.text,
                                    serviceId = resolved.id,
                                    serviceName = service.name,
                                    detectedLanguage = response.detectedLanguage,
                                    confidence = response.confidence,
                                )
                            )
                        },
                        failure = { error ->
                            OcrExtractionOutcome.Failure(
                                summary = error.shortSummary(),
                                errorType = error::class.simpleName ?: "ServiceError",
                            )
                        },
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error("OCR failed: ${error::class.simpleName}")
            OcrExtractionOutcome.Failure(
                summary = error.shortSummary(),
                errorType = error::class.simpleName ?: "Exception",
            )
        }

        return when (val completed = outcome) {
            null -> {
                logger.error("OCR timed out after ${OCR_TIMEOUT_MS}ms")
                onStatusUpdate(StatusCode.OcrTimeout, NotificationType.ERROR, true)
                OcrExtractionOutcome.Timeout
            }
            is OcrExtractionOutcome.Failure -> {
                logger.error("OCR failed: ${completed.errorType}")
                onStatusUpdate(StatusCode.OcrFailed(completed.summary), NotificationType.ERROR, true)
                completed
            }
            is OcrExtractionOutcome.Success -> {
                if (completed.result.text.isBlank()) {
                    logger.warn("No text detected in image")
                    onStatusUpdate(StatusCode.NoTextInImage, NotificationType.WARNING, true)
                    OcrExtractionOutcome.NoText
                } else {
                    logger.info(
                        "OCR successful: detected ${completed.result.text.length} characters, " +
                            "${completed.result.regions.size} region(s)"
                    )
                    onStatusUpdate(StatusCode.OcrComplete, NotificationType.SUCCESS, true)
                    completed
                }
            }
            OcrExtractionOutcome.NoService,
            OcrExtractionOutcome.NoText,
            OcrExtractionOutcome.Timeout -> completed
        }
    }

    /**
     * Historical entry point for callers that only need text. Kept as a thin adapter so the
     * existing privacy tests and any internal caller keep their simple contract.
     */
    suspend operator fun invoke(
        image: ImageData,
        currentState: MainState,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
    ): String = when (val outcome = extract(image, currentState.sourceLanguage, onStatusUpdate = onStatusUpdate)) {
        is OcrExtractionOutcome.Success -> outcome.result.text
        else -> ""
    }
}

/**
 * Applies the same line-break policy as manual input and Quick Translate. There is deliberately
 * one implementation: OCR's copy and translate routes share it, and the workspace uses it too.
 */
internal fun preprocessOcrText(text: String, settings: OcrSettings): String =
    prepareInputText(text, settings.removeLineBreaks)

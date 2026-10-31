package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearch
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearchRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal data class ImageLookupRequest(val term: String, val language: LanguageCode)
internal data class ImageLookupOutcome(
    val status: StatusCode, val type: NotificationType,
    val results: List<ImageResult>? = null, val failed: Boolean? = null
)

class SearchImagesUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("SearchImagesUseCase")

    internal suspend operator fun invoke(request: ImageLookupRequest, onStarted: suspend () -> Unit): ImageLookupOutcome {
        val (term, language) = request
        if (term.isBlank()) return ImageLookupOutcome(StatusCode.NoTermToIllustrate, NotificationType.WARNING)
        val service = activeServiceManager.getActiveService<ImageSearch>(ServiceRole.IMAGE_SEARCH)
            ?: return ImageLookupOutcome(StatusCode.NoImageSearchServiceActive, NotificationType.ERROR, emptyList(), true)
        return try {
            onStarted()
            val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
                service.searchImages(ImageSearchRequest(query = term, language = language))
            } ?: return ImageLookupOutcome(StatusCode.ImageSearchTimeout, NotificationType.WARNING, emptyList(), true)
            result.fold(
                success = { response ->
                    if (response.results.isEmpty()) ImageLookupOutcome(StatusCode.ImagesNotFound(term), NotificationType.INFO, emptyList())
                    else ImageLookupOutcome(StatusCode.ImageSearchReady, NotificationType.INFO, response.results, false)
                },
                failure = { error -> ImageLookupOutcome(StatusCode.ImageSearchFailed(error.toString()), NotificationType.ERROR, emptyList(), true) }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val summary = error.message ?: "Unknown error"
            logger.warn("Unexpected error during image search")
            ImageLookupOutcome(StatusCode.ImageSearchFailed(summary), NotificationType.ERROR, emptyList(), true)
        }
    }
}

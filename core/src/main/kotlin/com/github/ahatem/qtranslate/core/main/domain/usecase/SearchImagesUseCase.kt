package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearch
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearchRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Looks a term up as pictures rather than words.
 *
 * Deliberately shaped like [LookupWordUseCase]: same cancel-the-previous-one behaviour, same
 * timeout, and the same treatment of an empty result as information rather than failure. Someone
 * reading a hard text reaches for a definition and a picture in the same breath, and the two
 * should not behave differently when they do.
 */
class SearchImagesUseCase(
    private val scope: CoroutineScope,
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("SearchImagesUseCase")
    private var searchJob: Job? = null

    suspend operator fun invoke(
        term: String,
        language: LanguageCode,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (StatusCode, NotificationType, Boolean) -> Unit
    ) {
        // Typing in the popup's field fires a search per pause; the previous one is of no use
        // once its term is stale, and letting it finish would race the newer one into the state.
        searchJob?.cancel(CancellationException("New image search requested"))

        if (term.isBlank()) {
            onStatusUpdate(StatusCode.NoTermToIllustrate, NotificationType.WARNING, true)
            return
        }

        val service = activeServiceManager.getActiveService<ImageSearch>(ServiceType.IMAGE_SEARCH)
        if (service == null) {
            logger.warn("No image search service available")
            onStatusUpdate(StatusCode.NoImageSearchServiceActive, NotificationType.ERROR, true)
            updateState { copy(isImageSearchLoading = false, imageSearchFailed = true, imageResults = emptyList()) }
            return
        }

        logger.info("Searching images for '$term' with '${service.name}'")

        searchJob = scope.launch {
            try {
                onStatusUpdate(StatusCode.SearchingImages, NotificationType.INFO, false)
                updateState {
                    // Existing thumbnails stay up while the new search runs -- see LookupWordUseCase.
                    copy(
                        isImageSearchLoading = true,
                        imageSearchTerm = term,
                        imageSearchFailed = false
                    )
                }

                val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
                    service.searchImages(ImageSearchRequest(query = term, language = language))
                }

                if (result == null) {
                    logger.warn("Image search timed out for '$term'")
                    onStatusUpdate(StatusCode.ImageSearchTimeout, NotificationType.WARNING, true)
                    updateState { copy(isImageSearchLoading = false, imageSearchFailed = true, imageResults = emptyList()) }
                    return@launch
                }

                result.fold(
                    success = { response ->
                        if (response.results.isEmpty()) {
                            logger.info("No images found for '$term'")
                            onStatusUpdate(StatusCode.ImagesNotFound(term), NotificationType.INFO, true)
                            updateState { copy(isImageSearchLoading = false, imageResults = emptyList()) }
                        } else {
                            logger.info("Found ${response.results.size} images for '$term'")
                            onStatusUpdate(StatusCode.ImageSearchReady, NotificationType.INFO, true)
                            updateState {
                                copy(
                                    isImageSearchLoading = false,
                                    imageResults = response.results,
                                    imageSearchFailed = false
                                )
                            }
                        }
                    },
                    failure = { error ->
                        val summary = error.toString()
                        logger.warn("Image search failed for '$term': $summary")
                        onStatusUpdate(StatusCode.ImageSearchFailed(summary), NotificationType.ERROR, true)
                        updateState { copy(isImageSearchLoading = false, imageSearchFailed = true, imageResults = emptyList()) }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val summary = e.message ?: "Unknown error"
                logger.warn("Unexpected error during image search: $summary")
                onStatusUpdate(StatusCode.ImageSearchFailed(summary), NotificationType.ERROR, true)
                updateState { copy(isImageSearchLoading = false, imageSearchFailed = true, imageResults = emptyList()) }
            }
        }
    }
}

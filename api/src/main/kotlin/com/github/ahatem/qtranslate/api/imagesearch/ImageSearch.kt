package com.github.ahatem.qtranslate.api.imagesearch

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * Finds images for a term, so a reader can see what an unfamiliar word refers to.
 *
 * Aimed at understanding rather than decoration: someone reading technical material meets a word
 * like *borborygmi* and a picture explains it faster than a definition. That makes reference
 * imagery — encyclopaedic, scientific, diagrammatic — the useful kind here, and stock photography
 * close to useless.
 *
 * Results carry [ImageResult.attribution] and [ImageResult.license] because the sources best
 * suited to this are usually openly licensed, and those licences require credit to be shown
 * rather than merely recorded.
 */
interface ImageSearch : Service {

    /**
     * Searches for images matching [request].
     *
     * Finding nothing is not a failure: return an empty [ImageSearchResponse.results] so the host
     * can say "no images found", and reserve `Err` for searches that could not be performed.
     */
    suspend fun searchImages(request: ImageSearchRequest): Result<ImageSearchResponse, ServiceError>
}

/**
 * Parameters for an image search.
 *
 * @property query The term to look up.
 * @property language Language the term is in. Some sources hold far more material in one
 *   language than another, and a scientific term may resolve better in its original.
 * @property maxResults Upper bound on results. A service may return fewer.
 * @property options Chosen values keyed by
 *   [com.github.ahatem.qtranslate.api.plugin.ServiceOption.key] — safe search, media type, and
 *   anything else the service declares.
 */
data class ImageSearchRequest(
    val query: String,
    val language: LanguageCode,
    val maxResults: Int = 12,
    val options: Map<String, String> = emptyMap()
) {
    init {
        require(query.isNotBlank()) { "Image search query must not be blank." }
        require(maxResults > 0) { "maxResults must be positive, was $maxResults." }
    }
}

/** The results of an image search, empty when nothing matched. */
data class ImageSearchResponse(
    val results: List<ImageResult>
)

/**
 * One image.
 *
 * @property thumbnailUrl Small preview, loaded for the grid. Keep it small — a search shows
 *   many at once.
 * @property fullUrl Full-size image.
 * @property sourceUrl Page describing the image, opened when the user clicks through. This is
 *   usually more useful to a learner than the bare image.
 * @property title Caption, where the source provides one.
 * @property attribution Credit that must be displayed. Required by most open licences, so the
 *   host shows it beneath the image rather than hiding it behind an action.
 * @property license Licence name, e.g. `"CC BY-SA 4.0"`.
 */
data class ImageResult(
    val thumbnailUrl: String,
    val fullUrl: String,
    val sourceUrl: String? = null,
    val title: String? = null,
    val attribution: String? = null,
    val license: String? = null
) {
    init {
        require(thumbnailUrl.isNotBlank()) { "ImageResult.thumbnailUrl must not be blank." }
        require(fullUrl.isNotBlank()) { "ImageResult.fullUrl must not be blank." }
    }
}

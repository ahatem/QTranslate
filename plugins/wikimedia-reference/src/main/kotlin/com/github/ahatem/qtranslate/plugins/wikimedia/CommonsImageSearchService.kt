package com.github.ahatem.qtranslate.plugins.wikimedia

import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearch
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearchRequest
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearchResponse
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.ServiceOption
import com.github.ahatem.qtranslate.api.plugin.ServiceOptionValue
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.plugin.optionOrDefault
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import org.jsoup.Jsoup

/**
 * Finds reference images on Wikimedia Commons.
 *
 * Commons rather than a stock photo library because the point is to understand a word, not to
 * illustrate a page: searching a term like *borborygmi* on a stock site returns attractive
 * photographs of torsos, while Commons returns anatomy diagrams. It also needs no API key and its
 * material is openly licensed, which is what makes showing it here defensible at all.
 *
 * Every result carries its attribution and licence. Those licences require credit on display, so
 * the fields are populated here rather than left to the host to chase.
 */
internal class CommonsImageSearchService(private val client: WikimediaClient) : ImageSearch {

    override val key = "wikimedia-commons-images"
    override val name = "Wikimedia Commons"
    override val iconPath = "assets/commons.svg"
    override val version = "1.0.0"

    /**
     * Commons is a single wiki shared by every language edition rather than one wiki per
     * language, and its search matches file captions in all of them, so no language is excluded.
     */
    override val supportedLanguages = SupportedLanguages.All

    override val metadata = ServiceMetadata(
        isFree = true,
        homepage = "https://commons.wikimedia.org",
        attribution = DisplayText(
            "wikimedia.images_attribution",
            "Images from Wikimedia Commons, under the licence shown with each result."
        ),
        notes = DisplayText(
            "wikimedia.images_privacy_note",
            "The selected word is sent to Wikimedia Commons to search for images."
        )
    )

    override val options = listOf(MEDIA_TYPE)

    override suspend fun searchImages(
        request: ImageSearchRequest
    ): Result<ImageSearchResponse, ServiceError> {
        val mediaType = request.options.optionOrDefault(MEDIA_TYPE, KEY_MEDIA_TYPE, BOTH)

        return client.searchImages(
            query = request.query,
            limit = request.maxResults,
            fileTypes = fileTypesFor(mediaType),
            thumbnailWidth = THUMBNAIL_WIDTH
        ).map { pages ->
            ImageSearchResponse(pages.mapNotNull(::toResult))
        }
    }

    /**
     * Drops a file rather than showing a broken tile when Commons returns no thumbnail — which
     * happens for formats it cannot rasterize.
     */
    private fun toResult(page: CommonsPage): ImageResult? {
        val info = page.imageInfo.firstOrNull() ?: return null
        val thumbnail = info.thumbnailUrl ?: return null
        val full = info.url ?: return null

        return ImageResult(
            thumbnailUrl = thumbnail,
            fullUrl = full,
            sourceUrl = info.descriptionUrl,
            // "File:Sound of borborygmi.png" — the prefix and extension are noise in a caption.
            title = page.title.removePrefix("File:").substringBeforeLast('.').trim()
                .takeIf { it.isNotBlank() },
            attribution = info.extraMetadata[ARTIST]?.text?.let(::stripMarkup),
            license = info.extraMetadata[LICENSE]?.text?.let(::stripMarkup)
        )
    }

    /** `extmetadata` credits arrive as HTML, usually a link round the author's name. */
    private fun stripMarkup(value: String): String? =
        Jsoup.parseBodyFragment(value).text().trim().takeIf { it.isNotBlank() }

    private fun fileTypesFor(mediaType: String): String = when (mediaType) {
        PHOTOS -> "bitmap"
        DIAGRAMS -> "drawing"
        else -> "bitmap|drawing"
    }

    private companion object {
        const val KEY_MEDIA_TYPE = "mediaType"
        const val PHOTOS = "PHOTOS"
        const val DIAGRAMS = "DIAGRAMS"
        const val BOTH = "BOTH"

        const val ARTIST = "Artist"
        const val LICENSE = "LicenseShortName"

        /**
         * Wide enough that the host can enlarge one of these without it going soft, small enough
         * that a dozen still arrive quickly. Commons originals run to tens of megabytes, so asking
         * for the full file instead would be far worse than a slightly larger thumbnail.
         */
        const val THUMBNAIL_WIDTH = 480

        /**
         * A vocabulary the host does not own, supplied with the plugin's own translations.
         *
         * Diagrams and photographs answer different questions — an anatomical drawing explains a
         * medical term better than any photograph, while a photograph settles what a physical
         * object looks like — so which one a reader wants depends on what they are reading.
         */
        val MEDIA_TYPE = ServiceOption(
            key = KEY_MEDIA_TYPE,
            label = DisplayText("wikimedia.media_type", "Show"),
            values = listOf(
                ServiceOptionValue(BOTH, DisplayText("wikimedia.media_type_both", "Anything")),
                ServiceOptionValue(PHOTOS, DisplayText("wikimedia.media_type_photos", "Photographs")),
                ServiceOptionValue(
                    DIAGRAMS,
                    DisplayText("wikimedia.media_type_diagrams", "Diagrams")
                )
            ),
            defaultValue = BOTH
        )
    }
}

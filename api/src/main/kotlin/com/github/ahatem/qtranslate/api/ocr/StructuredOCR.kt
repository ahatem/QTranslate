package com.github.ahatem.qtranslate.api.ocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * Optional OCR capability for services that can report where recognized text sits in the image.
 *
 * This is a separate interface rather than new fields on [OCRResponse] so the existing public
 * response type keeps its exact constructor and binary shape. A third-party plugin compiled
 * against an older API remains a perfectly valid [OCR]; the host only asks for geometry when the
 * service also implements this interface. Text-only services continue to work unchanged.
 *
 * Coordinates are measured in pixels from the top-left corner of the image that was supplied in
 * [OCRRequest.image], even if the service internally resized the image before recognition.
 * Providers that cannot map their internal geometry back to the original image must not implement
 * this interface; inventing or mis-scaled boxes is worse than showing plain text.
 */
public interface StructuredOCR : OCR {

    /**
     * Extracts text and the optional regions it occupies.
     *
     * Implementations should return the same text [OCR.extractText] would. The response may
     * contain no regions when the provider genuinely found none.
     */
    public suspend fun extractStructuredText(
        request: OCRRequest
    ): Result<StructuredOCRResponse, ServiceError>
}

/** The semantic level of one [OCRTextRegion]. */
public enum class OCRRegionKind {
    WORD,
    LINE,
    BLOCK
}

/**
 * One recognized region and its rectangle in source-image pixels.
 *
 * @param text The text covered by this region. May be empty for a purely spatial block.
 * @param left Distance from the image's left edge to the region's left edge, in pixels.
 * @param top Distance from the image's top edge to the region's top edge, in pixels.
 * @param right Distance from the image's left edge to the region's right edge, in pixels.
 * @param bottom Distance from the image's top edge to the region's bottom edge, in pixels.
 * @param kind What this region represents. Providers should report the finest useful level.
 * @param confidence Optional provider confidence in `[0.0, 1.0]`, or null when not supplied.
 */
public data class OCRTextRegion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val kind: OCRRegionKind = OCRRegionKind.LINE,
    val confidence: Float? = null
) {
    init {
        require(right >= left) { "OCR region right edge must not precede its left edge." }
        require(bottom >= top) { "OCR region bottom edge must not precede its top edge." }
        require(confidence == null || confidence in 0f..1f) {
            "OCR region confidence must be in [0.0, 1.0], but was $confidence."
        }
    }
}

/**
 * Structured OCR result.
 *
 * @param text The complete recognized text, in reading order.
 * @param regions Optional spatial regions. Empty is valid and means "this result has no geometry".
 * @param confidence Optional whole-result confidence, or null when not supplied.
 * @param detectedLanguage The language detected in the image, if the provider reports one.
 */
public data class StructuredOCRResponse(
    val text: String,
    val regions: List<OCRTextRegion> = emptyList(),
    val confidence: Float? = null,
    val detectedLanguage: LanguageCode? = null
) {
    init {
        require(confidence == null || confidence in 0f..1f) {
            "OCR confidence score must be in [0.0, 1.0], but was $confidence."
        }
    }
}

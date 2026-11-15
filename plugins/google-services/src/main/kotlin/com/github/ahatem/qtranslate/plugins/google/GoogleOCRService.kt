package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCRRegionKind
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.api.ocr.StructuredOCR
import com.github.ahatem.qtranslate.api.ocr.StructuredOCRResponse
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.sendJson
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.google.common.*
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr
import java.util.*
import kotlin.math.roundToInt

class GoogleOCRService(
    private val pluginContext: PluginContext,
    private val settings: GoogleSettings,
    private val httpClient: HttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : StructuredOCR {


    override val key: String = "google-ocr"
    override val name: String = "Google OCR"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    private val parser = createJsonParser<VisionResponse>(pluginContext)

    companion object {
        private const val VISION_ENDPOINT = "https://vision.googleapis.com/v1/images:annotate"
    }

    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
        annotate(request) map { annotation ->
            val full = annotation?.fullTextAnnotation
            OCRResponse(
                text = full?.text?.trim().orEmpty(),
                confidence = null,
                detectedLanguage = full?.primaryLanguage(languageMapper),
            )
        }

    /**
     * Text and, where Vision supplies it, geometry.
     *
     * Vision reports a polygon for every block, paragraph and word, plus per-word confidence.
     * Words are the finest level that still reads as text, so they are what the workspace draws;
     * block and paragraph boxes add nothing a reader can see at that size. Symbols are not used:
     * they are single characters, and one box per character is noise rather than a highlight.
     */
    override suspend fun extractStructuredText(
        request: OCRRequest
    ): Result<StructuredOCRResponse, ServiceError> =
        annotate(request) map { annotation ->
            val full = annotation?.fullTextAnnotation
            if (full == null) {
                StructuredOCRResponse(text = "")
            } else {
                StructuredOCRResponse(
                    text = full.text.trim(),
                    regions = full.textRegions(request.image.width, request.image.height),
                    detectedLanguage = full.primaryLanguage(languageMapper),
                )
            }
        }

    /**
     * Sends the image once and returns Vision's annotation for it.
     *
     * Both entry points go through here so they cannot drift: the text the workspace shows and the
     * text it draws boxes around come from the same response.
     */
    private suspend fun annotate(request: OCRRequest): Result<VisionAnnotation?, ServiceError> {
        if (settings.visionApiKey.isBlank()) {
            return Err(
                ServiceError.AuthenticationError(
                    "OCR requires a Vision API key. Please configure your Google Cloud Vision API key in settings."
                )
            )
        }

        return coroutineBinding {
            val base64Image = Base64.getEncoder().encodeToString(request.image.bytes)

            val requestBody = VisionRequest(
                requests = listOf(
                    VisionImageRequest(
                        image = VisionImage(content = base64Image),
                        features = listOf(VisionFeature(type = "TEXT_DETECTION")),
                        imageContext = if (request.language != LanguageCode.AUTO) {
                            VisionImageContext(languageHints = listOf(languageMapper.toProviderCode(request.language)))
                        } else null
                    )
                )
            )

            val responseString = httpClient.sendJson(
                url = VISION_ENDPOINT,
                headers = apiConfig.createJsonHeaders(),
                body = requestBody,
                queryParams = mapOf("key" to settings.visionApiKey)
            ).bind()

            val parsed = parser.parse(responseString).bind()
            val firstResponse = parsed.responses.firstOrNull()
                .toResultOr { ServiceError.InvalidResponseError("No response from Vision API", null) }
                .bind()

            firstResponse.error?.let { error ->
                Err(ServiceError.UnknownError(error.message, null)).bind()
            }

            firstResponse
        }
    }
}

/** The page's own language guess, which is the first language of the first page. */
private fun FullTextAnnotation.primaryLanguage(languageMapper: GoogleLanguageMapper): LanguageCode? =
    pages?.firstOrNull()?.property?.detectedLanguages?.firstOrNull()?.languageCode
        ?.takeIf { it.isNotBlank() }
        ?.let { languageMapper.fromProviderCode(it) }

/**
 * Word boxes in the caller's pixels.
 *
 * Vision boxes are in the pixels of the image it analyzed, reported on the page as `width` and
 * `height`. Those are usually the upload's own size, but Vision may scale or rotate a large
 * upload, so every box is mapped to the caller's original dimensions. When the page does not
 * report its size there is nothing to map from, and returning text without boxes is correct:
 * mis-scaled highlights are worse than none.
 */
private fun FullTextAnnotation.textRegions(originalWidth: Int, originalHeight: Int): List<OCRTextRegion> =
    pages.orEmpty().flatMap { page ->
        if (page.width <= 0 || page.height <= 0) return@flatMap emptyList()
        val scaleX = if (originalWidth > 0) originalWidth.toDouble() / page.width else 1.0
        val scaleY = if (originalHeight > 0) originalHeight.toDouble() / page.height else 1.0
        page.blocks.orEmpty().flatMap { block ->
            block.paragraphs.orEmpty().flatMap { paragraph ->
                paragraph.words.orEmpty().mapNotNull { word ->
                    word.region(scaleX, scaleY, originalWidth, originalHeight)
                }
            }
        }
    }

private fun VisionWord.region(
    scaleX: Double,
    scaleY: Double,
    maxWidth: Int,
    maxHeight: Int,
): OCRTextRegion? {
    val box = boundingBox?.vertices.orEmpty()
    if (box.isEmpty()) return null
    val text = symbols.orEmpty().joinToString("") { it.text }
    if (text.isBlank()) return null

    val left = (box.minOf { it.x } * scaleX).roundToInt().coerceIn(0, maxWidth.coerceAtLeast(0))
    val top = (box.minOf { it.y } * scaleY).roundToInt().coerceIn(0, maxHeight.coerceAtLeast(0))
    val right = (box.maxOf { it.x } * scaleX).roundToInt().coerceIn(left, maxWidth.coerceAtLeast(left))
    val bottom = (box.maxOf { it.y } * scaleY).roundToInt().coerceIn(top, maxHeight.coerceAtLeast(top))

    return OCRTextRegion(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        kind = OCRRegionKind.WORD,
        confidence = confidence?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
    )
}

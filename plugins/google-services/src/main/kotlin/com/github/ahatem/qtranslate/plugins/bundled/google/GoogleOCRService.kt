package com.github.ahatem.qtranslate.plugins.bundled.google

import com.github.ahatem.qtranslate.api.*
import com.github.ahatem.qtranslate.plugins.bundled.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.bundled.google.common.VisionFeature
import com.github.ahatem.qtranslate.plugins.bundled.google.common.VisionImage
import com.github.ahatem.qtranslate.plugins.bundled.google.common.VisionImageContext
import com.github.ahatem.qtranslate.plugins.bundled.google.common.VisionImageRequest
import com.github.ahatem.qtranslate.plugins.bundled.google.common.VisionRequest
import com.github.ahatem.qtranslate.plugins.bundled.google.common.VisionResponse
import com.github.ahatem.qtranslate.plugins.common.*
import com.github.michaelbull.result.*
import com.github.michaelbull.result.coroutines.coroutineBinding
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

class GoogleOCRService(
    private val pluginContext: PluginContext,
    private val settings: GoogleSettings,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : OCR {

    override val id: String = "google-services-ocr"
    override val name: String = "Google OCR"
    override val version: String = "1.0.0"

    private val parser = createJsonParser<VisionResponse>(pluginContext)

    companion object {
        private const val VISION_ENDPOINT = "https://vision.googleapis.com/v1/images:annotate"
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        return languageMapper.getSupportedLanguages()
    }

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> {
        if (settings.visionApiKey.isBlank()) {
            return Err(
                ServiceError.AuthenticationError(
                    "OCR requires a Vision API key. Please configure your Google Cloud Vision API key in settings."
                )
            )
        }

        return coroutineBinding {
            val base64Image = encodeImageToBase64(request.image).bind()

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

            val responseString = httpClient.postTyped(
                url = VISION_ENDPOINT,
                body = requestBody,
                headers = apiConfig.createJsonHeaders(),
                queryParams = mapOf("key" to settings.visionApiKey)
            ).bind()

            val parsed = parser.parse(responseString).bind()
            val firstResponse = parsed.responses.firstOrNull()
                .toResultOr { ServiceError.InvalidResponseError("No response from Vision API", null) }
                .bind()

            firstResponse.error?.let { error ->
                Err(ServiceError.UnknownError(error.message, null)).bind()
            }

            val fullTextAnnotation = firstResponse.fullTextAnnotation

            if (fullTextAnnotation == null) {
                OCRResponse(text = "", confidence = null, detectedLanguage = null)
            } else {
                val detectedLang = fullTextAnnotation.pages
                    ?.firstOrNull()?.property?.detectedLanguages?.firstOrNull()?.languageCode
                    ?.let { languageMapper.fromProviderCode(it) }

                OCRResponse(
                    text = fullTextAnnotation.text.trim(),
                    confidence = null,
                    detectedLanguage = detectedLang
                )
            }
        }
    }

    private fun encodeImageToBase64(image: java.awt.image.BufferedImage): Result<String, ServiceError> {
        return try {
            val outputStream = ByteArrayOutputStream()
            ImageIO.write(image, "PNG", outputStream)
            val imageBytes = outputStream.toByteArray()
            Ok(Base64.getEncoder().encodeToString(imageBytes))
        } catch (e: Exception) {
            pluginContext.logError(e, "Failed to encode image")
            Err(ServiceError.InvalidInputError("Failed to encode image: ${e.message}", e))
        }
    }
}

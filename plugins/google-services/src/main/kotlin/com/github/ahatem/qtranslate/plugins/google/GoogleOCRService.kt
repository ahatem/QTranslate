package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.*
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.google.common.*
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr
import java.awt.image.BufferedImage
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

    private fun encodeImageToBase64(image: BufferedImage): Result<String, ServiceError> {
        return try {
            val outputStream = ByteArrayOutputStream()
            ImageIO.write(image, "PNG", outputStream)
            val imageBytes = outputStream.toByteArray()
            Ok(Base64.getEncoder().encodeToString(imageBytes))
        } catch (e: Exception) {
            pluginContext.logger.error("Failed to encode image", e)
            Err(ServiceError.InvalidInputError("Failed to encode image: ${e.message}", e))
        }
    }
}

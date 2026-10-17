package com.github.ahatem.qtranslate.plugins.lens

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleLensOCRService(
    private val pluginContext: PluginContext,
    private val httpClient: HttpClient = pluginContext.http
) : OCR {

    override val key: String = "google-lens-ocr"
    override val name: String = "Google Lens OCR"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/lens-icon.svg"

    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    private val languageMapper = LensLanguageMapper

    companion object {
        private const val LENS_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"

        // Public client key used by Chromium for Lens overlay; see Chromium source tree:
        // https://source.chromium.org/chromium/chromium/src/+/main:components/lens/lens_features.cc
        private const val LENS_API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
        withContext(Dispatchers.IO) {
            val protoPayload = try {
                val imageBytes = request.image.bytes
                val width = if (request.image.width > 0) request.image.width else 1000
                val height = if (request.image.height > 0) request.image.height else 1000
                val langTag = if (request.language.tag != "auto") {
                    languageMapper.toProviderCode(request.language)
                } else {
                    "en"
                }

                GoogleLensProto.buildRequest(
                    imageBytes = imageBytes,
                    width = width,
                    height = height,
                    language = langTag
                )
            } catch (e: Exception) {
                pluginContext.logger.error("Failed to build Google Lens request", e)
                return@withContext Err(ServiceError.InvalidInputError("Failed to build Google Lens request", e))
            }

            val headers = mapOf(
                "Content-Type" to "application/x-protobuf",
                "X-Goog-Api-Key" to LENS_API_KEY,
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*"
            )

            val responseResult = httpClient.postBytes(
                url = LENS_ENDPOINT,
                headers = headers,
                body = protoPayload
            )

            responseResult.andThen { responseBody ->
                try {
                    val parseResult = GoogleLensProto.parseResponse(responseBody)
                    val recognizedText = parseResult.text.trim()

                    if (recognizedText.isBlank()) {
                        Err(
                            ServiceError.InvalidResponseError(
                                "No text detected in the image"
                            )
                        )
                    } else {
                        val detectedLang = parseResult.language?.let {
                            languageMapper.fromProviderCode(it)
                        }

                        Ok(
                            OCRResponse(
                                text = recognizedText,
                                confidence = null,
                                detectedLanguage = detectedLang
                            )
                        )
                    }
                } catch (e: Exception) {
                    pluginContext.logger.error("Failed to parse Google Lens OCR response", e)
                    Err(ServiceError.InvalidResponseError("Failed to parse Google Lens response", e))
                }
            }
        }
}

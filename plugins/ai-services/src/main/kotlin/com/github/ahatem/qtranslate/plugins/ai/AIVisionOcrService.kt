package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceCapability
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * AI-powered OCR service using multimodal (vision) models via the OpenAI-compatible API.
 *
 * ### Model requirement
 * The active model in AI Plugin settings **must support vision** (image input).
 * Examples of vision-capable models available on OpenRouter:
 * - `openai/gpt-4o` · `openai/gpt-4o-mini`
 * - `google/gemini-flash-1.5` · `google/gemini-2.0-flash`
 * - `anthropic/claude-3-5-sonnet` · `anthropic/claude-3-haiku`
 * - `meta-llama/llama-3.2-11b-vision-instruct`
 *
 * Text-only models will return an error — configure a vision-capable model first.
 *
 * ### What it extracts
 * - All visible text, preserving original line structure as closely as possible.
 * - Optionally detects the text language and returns it as [OCRResponse.detectedLanguage].
 * - Does not return a confidence score (language models don't produce calibrated confidence).
 */
class AIVisionOcrService(
    private val client: AIServiceClient
) : OCR {

    override val capabilities = setOf(ServiceCapability.OCR)

    // Nothing this plugin offers works until a key is set, which is what makes a connection
    // test worth offering here. The check itself is shared: one key, one endpoint, one model.
    override val metadata = ServiceMetadata(requiresConfiguration = true)

    override suspend fun validate() = client.validate()

    override val key: String = "ai-ocr"
    override val name: String = "AI Vision OCR"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/ai-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
        coroutineBinding {
            val languageHint = buildLanguageHint(request.language)

            val system = """
                You are a precise OCR engine.
                Extract ALL text visible in the image exactly as it appears.
                $languageHint

                You MUST respond with a single valid JSON object and NOTHING else. No markdown wrapping.

                Schema:
                {
                  "text": "full extracted text preserving line breaks with \\n",
                  "detected_language": "BCP-47 tag of the primary language in the image, or null if uncertain"
                }

                Rules:
                - Preserve the original text layout using line breaks (\\n) where appropriate.
                - Do NOT correct spelling errors — extract exactly what is written.
                - Do NOT add explanations, translations, or any text not present in the image.
                - If no text is found, return {"text": "", "detected_language": null}.
                - If the image contains text in multiple languages, set detected_language to the dominant one.
            """.trimIndent()

            val raw = client.completeWithImage(
                system    = system,
                image     = request.image,
                userText  = "Extract all text from this image."
            ).bind()

            parseOcrResponse(raw).bind()
        }

    private fun buildLanguageHint(language: LanguageCode): String {
        if (language == LanguageCode.AUTO) return ""
        val langName = Locale.forLanguageTag(language.tag)
            .getDisplayLanguage(Locale.ENGLISH)
            .ifBlank { language.tag }
        return "The image is expected to contain text in $langName — use this as a hint to improve recognition accuracy."
    }

    private fun parseOcrResponse(raw: String): Result<OCRResponse, ServiceError> =
        try {
            val clean = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val dto = json.decodeFromString<OcrResponseDto>(clean)

            val detectedLang = dto.detectedLanguage
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { tag -> runCatching { LanguageCode(tag) }.getOrNull() }

            Ok(
                OCRResponse(
                    text              = dto.text,
                    confidence        = null,   // LLMs don't produce calibrated confidence scores
                    detectedLanguage  = detectedLang
                )
            )
        } catch (e: Exception) {
            Err(ServiceError.InvalidResponseError("Failed to parse AI OCR response: $raw", e))
        }

    // ─── Private DTOs ────────────────────────────────────────────────────────

    @Serializable
    private data class OcrResponseDto(
        @SerialName("text")               val text: String = "",
        @SerialName("detected_language")  val detectedLanguage: String? = null
    )
}

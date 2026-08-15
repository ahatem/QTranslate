package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceCapability
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

val LanguageCode.displayName: String
    get() = if (tag == "auto") "Auto-detect"
    else Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)

class AITranslatorService(
    private val client: AIServiceClient
) : Translator {

    override val capabilities = setOf(ServiceCapability.TRANSLATOR)

    // Nothing this plugin offers works until a key is set, which is what makes a connection
    // test worth offering here. The check itself is shared: one key, one endpoint, one model.
    override val metadata = ServiceMetadata(requiresConfiguration = true)

    override suspend fun validate() = client.validate()

    override val key: String = "ai-translator"
    override val name: String = "AI Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/ai-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
        coroutineBinding {
            if (request.sourceLanguage == LanguageCode.AUTO) {
                translateWithAutoDetect(request).bind()
            } else {
                translateDirect(request).bind()
            }
        }

    private suspend fun translateDirect(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        val source = request.sourceLanguage.displayName
        val target = request.targetLanguage.displayName

        val system = """
            You are a professional translator.
            Translate the user's text from $source to $target.
            Output ONLY the translated text — no labels, no explanations, no quotes.
            Preserve the original formatting and line breaks.
        """.trimIndent()

        return client.complete(system, request.text).map { translatedText ->
            TranslationResponse(translatedText = translatedText.trim())
        }
    }

    private suspend fun translateWithAutoDetect(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
        coroutineBinding {
            val targetName = request.targetLanguage.displayName
            val targetTag  = request.targetLanguage.tag

            val system = """
                You are a professional translator.
                Your task: Detect the source language and translate the text into $targetName (BCP-47 tag: '$targetTag').

                Strict Rule: You must output ONLY a valid JSON object. No markdown formatting.

                Schema:
                {
                  "translation": "translated text here",
                  "detected_language": "BCP-47 tag of source"
                }

                Preserve formatting, line breaks, and punctuation.
            """.trimIndent()

            val raw = client.complete(system, request.text).bind()
            parseAutoDetectResponse(raw).bind()
        }

    private fun parseAutoDetectResponse(raw: String): Result<TranslationResponse, ServiceError> =
        try {
            // Strip optional markdown code fences some models add despite instructions
            val clean = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val dto = json.decodeFromString<AutoDetectResponseDto>(clean)

            val detectedLang = dto.detectedLanguage
                ?.let { tag -> runCatching { LanguageCode(tag) }.getOrNull() }

            Ok(TranslationResponse(translatedText = dto.translation.trim(), detectedLanguage = detectedLang))
        } catch (e: Exception) {
            Err(ServiceError.InvalidResponseError("Failed to parse AI translation response: $raw", e))
        }

    @Serializable
    private data class AutoDetectResponseDto(
        @SerialName("translation")       val translation: String,
        @SerialName("detected_language") val detectedLanguage: String? = null
    )
}

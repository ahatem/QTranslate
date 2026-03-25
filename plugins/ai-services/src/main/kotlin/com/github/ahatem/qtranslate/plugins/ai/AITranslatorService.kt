package com.github.ahatem.qtranslate.plugins.ai

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

class AITranslatorService(
    private val client: AIServiceClient,
    private val settings: () -> AISettings
) : Translator {

    override val id: String = "ai-translator"
    override val name: String get() = "${settings().provider} Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/ai-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
        coroutineBinding {
            if (request.sourceLanguage == LanguageCode.AUTO) {
                translateWithAutoDetect(request).bind()
            } else {
                translateDirect(request).bind()
            }
        }

    private suspend fun translateDirect(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        val system = """
            You are a professional translator.
            Translate the user's text from ${request.sourceLanguage.tag} to ${request.targetLanguage.tag}.
            Output ONLY the translated text — no labels, no explanations, no quotes.
            Preserve the original formatting, line breaks, and punctuation style.
        """.trimIndent()

        return client.complete(system, request.text, jsonMode = false).map { translatedText ->
            TranslationResponse(translatedText = translatedText.trim())
        }
    }

    private suspend fun translateWithAutoDetect(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
        coroutineBinding {
            val system = """
                You are a professional translator.
                Detect the language of the user's text and translate it to ${request.targetLanguage.tag}.
                You MUST respond with a valid JSON object and nothing else — no markdown fences, no prose.
                Schema:
                {
                  "translation": "<translated text>",
                  "detected_language": "<BCP-47 tag of source language, e.g. en, fr, zh-Hans>"
                }
                Preserve original formatting and line breaks inside the translation value.
            """.trimIndent()

            val raw = client.complete(system, request.text, jsonMode = true).bind()
            parseAutoDetectResponse(raw).bind()
        }

    private fun parseAutoDetectResponse(raw: String): Result<TranslationResponse, ServiceError> {
        return try {
            val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val translationMatch = Regex(""""translation"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(clean)
            val detectedMatch = Regex(""""detected_language"\s*:\s*"([^"]+)"""").find(clean)

            val translatedText = translationMatch?.groupValues?.get(1)
                ?: return Err(
                    ServiceError.InvalidResponseError(
                        "Could not parse translation from AI response: $raw", null
                    )
                )

            val detectedLanguage = detectedMatch?.groupValues?.get(1)
                ?.let { tag -> runCatching { LanguageCode(tag) }.getOrNull() }

            Ok(
                TranslationResponse(
                    translatedText = translatedText
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .trim(),
                    detectedLanguage = detectedLanguage
                )
            )
        } catch (e: Exception) {
            Err(ServiceError.InvalidResponseError("Failed to parse AI translation response.", e))
        }
    }
}
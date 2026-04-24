package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.*
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AISpellCheckerService(
    private val client: AIServiceClient,
    private val settings: () -> AISettings
) : SpellChecker {

    override val id: String = "ai-spell-checker"
    override val name: String get() = "${settings().provider} Spell Checker"
    override val version: String = "1.0.0"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> =
        coroutineBinding {
            if (request.text.isBlank()) {
                return@coroutineBinding Ok(SpellCheckResponse(request.text, emptyList())).bind()
            }

            // Use displayName to avoid the "it" pronoun bug
            val languageInstruction = if (request.language == LanguageCode.AUTO) {
                "Detect the language of the text automatically."
            } else {
                "The text is written in ${request.language.displayName} (BCP-47: '${request.language.tag}'). Use grammar and spelling rules specific to this language."
            }

            val system = """
            You are a precise spell-checking and grammar assistant.
            
            Instruction:
            $languageInstruction
            
            Analyze the user's text for spelling, grammar, style, and punctuation errors.
            You MUST respond with a single valid JSON object and NOTHING else. No markdown wrapping.
            
            Schema:
            {
              "corrected_text": "full text with corrections applied",
              "corrections": [
                {
                  "original": "incorrect span",
                  "corrected": "replacement",
                  "start_index": 0,
                  "end_index": 5,
                  "type": "SPELLING",
                  "message": "explanation"
                }
              ]
            }
            
            Note: If no errors are found, return an empty array for 'corrections' and the original text for 'corrected_text'.
            IMPORTANT: 'start_index' and 'end_index' must reference the character offsets in the ORIGINAL input string.
        """.trimIndent()

            val raw = client.complete(system, request.text, jsonMode = true).bind()
            parseSpellCheckResponse(raw, request.text).bind()
        }

    private fun parseSpellCheckResponse(
        raw: String,
        originalText: String
    ): Result<SpellCheckResponse, ServiceError> {
        return try {
            val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val dto = json.decodeFromString<SpellCheckResponseDto>(clean)

            val corrections = dto.corrections.mapNotNull { item ->
                runCatching {
                    val start = item.startIndex.coerceIn(0, originalText.length)
                    val end = item.endIndex.coerceIn(start + 1, originalText.length)
                    Correction(
                        original = item.original,
                        startIndex = start,
                        endIndex = end,
                        suggestions = listOf(item.corrected),
                        type = when (item.type.uppercase()) {
                            "GRAMMAR" -> CorrectionType.GRAMMAR
                            "STYLE" -> CorrectionType.STYLE
                            "PUNCTUATION" -> CorrectionType.PUNCTUATION
                            else -> CorrectionType.SPELLING
                        },
                        message = item.message
                    )
                }.getOrNull()
            }

            Ok(SpellCheckResponse(correctedText = dto.correctedText, corrections = corrections))
        } catch (e: Exception) {
            Err(ServiceError.InvalidResponseError("Failed to parse spell-check response.", e))
        }
    }

    @Serializable
    private data class SpellCheckResponseDto(
        @SerialName("corrected_text") val correctedText: String,
        @SerialName("corrections") val corrections: List<CorrectionDto> = emptyList()
    )

    @Serializable
    private data class CorrectionDto(
        @SerialName("original") val original: String,
        @SerialName("corrected") val corrected: String,
        @SerialName("start_index") val startIndex: Int,
        @SerialName("end_index") val endIndex: Int,
        @SerialName("type") val type: String = "SPELLING",
        @SerialName("message") val message: String? = null
    )
}
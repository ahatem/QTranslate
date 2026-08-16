package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata

import com.github.ahatem.qtranslate.api.dictionary.*
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

class AIDictionaryService(
    private val client: AIServiceClient
) : Dictionary {


    // Nothing this plugin offers works until a key is set, which is what makes a connection
    // test worth offering here. The check itself is shared: one key, one endpoint, one model.
    override val metadata = ServiceMetadata(requiresConfiguration = true)

    override suspend fun validate() = client.validate()

    override val key: String = "ai-dictionary"
    override val name: String = "AI Dictionary"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/ai-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> =
        coroutineBinding {
            val languageName = Locale.forLanguageTag(request.language.tag)
                .getDisplayLanguage(Locale.ENGLISH)
                .ifBlank { request.language.tag }

            val system = """
                You are a professional dictionary.
                Look up the word or phrase provided by the user in $languageName.

                You MUST respond with a single valid JSON object and NOTHING else. No markdown wrapping.

                Schema:
                {
                  "entries": [
                    {
                      "word": "canonical/base form of the word",
                      "part_of_speech": "noun | verb | adjective | adverb | pronoun | preposition | conjunction | interjection | phrase",
                      "phonetic": "/fəˈnɛtɪk/ in IPA notation, or null if unknown",
                      "definitions": [
                        {
                          "text": "clear definition in $languageName",
                          "example": "a natural usage example sentence, or null"
                        }
                      ],
                      "synonyms": ["synonym1", "synonym2"]
                    }
                  ]
                }

                Rules:
                - Provide up to 3 entries for distinct senses or parts of speech.
                - Each entry should have 1–3 definitions.
                - Synonyms list may be empty if none apply.
                - If the word is not found or is not a real word, return {"entries": []}.
                - Do NOT include antonyms, translations, or any fields not in the schema.
            """.trimIndent()

            val raw = client.complete(system, request.word).bind()
            parseDictionaryResponse(raw).bind()
        }

    private fun parseDictionaryResponse(raw: String): Result<DictionaryResponse, ServiceError> =
        try {
            val clean = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val dto = json.decodeFromString<DictionaryResponseDto>(clean)

            Ok(
                DictionaryResponse(
                    entries = dto.entries.map { entry ->
                        DictionaryEntry(
                            word         = entry.word,
                            partOfSpeech = entry.partOfSpeech,
                            definitions  = entry.definitions.map { def ->
                                Definition(text = def.text, example = def.example)
                            },
                            synonyms  = entry.synonyms,
                            phonetic  = entry.phonetic?.takeIf { it.isNotBlank() }
                        )
                    }
                )
            )
        } catch (e: Exception) {
            Err(ServiceError.InvalidResponseError("Failed to parse AI dictionary response: $raw", e))
        }

    // ─── Private DTOs ────────────────────────────────────────────────────────

    @Serializable
    private data class DictionaryResponseDto(
        @SerialName("entries") val entries: List<DictionaryEntryDto> = emptyList()
    )

    @Serializable
    private data class DictionaryEntryDto(
        @SerialName("word")          val word: String,
        @SerialName("part_of_speech") val partOfSpeech: String,
        @SerialName("phonetic")      val phonetic: String? = null,
        @SerialName("definitions")   val definitions: List<DefinitionDto> = emptyList(),
        @SerialName("synonyms")      val synonyms: List<String> = emptyList()
    )

    @Serializable
    private data class DefinitionDto(
        @SerialName("text")    val text: String,
        @SerialName("example") val example: String? = null
    )
}

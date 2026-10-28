package com.github.ahatem.qtranslate.plugins.speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An OpenAI-style `/audio/transcriptions` reply, in `json` or `verbose_json` format. */
@Serializable
data class OpenAiTranscription(
    val text: String? = null,
    /** The spoken language's English name ("english"), not a code. Verbose format only. */
    val language: String? = null,
    val words: List<OpenAiWord>? = null,
    val error: OpenAiError? = null
)

/** Times are seconds from the start of the clip. */
@Serializable
data class OpenAiWord(val word: String, val start: Double, val end: Double)

@Serializable
data class OpenAiError(val message: String)

/** An OpenAI-style `/models` listing. */
@Serializable
data class ModelList(val data: List<ModelEntry> = emptyList())

@Serializable
data class ModelEntry(val id: String)

/** A `/chat/completions` reply; only the fields used are declared. */
@Serializable
data class ChatReply(
    val choices: List<ChatChoice> = emptyList(),
    val error: OpenAiError? = null
)

@Serializable
data class ChatChoice(val message: ChatMessage? = null)

@Serializable
data class ChatMessage(val content: String? = null)

/** A Deepgram `/listen` reply; only the fields used are declared. */
@Serializable
data class DeepgramResponse(val results: DeepgramResults? = null)

@Serializable
data class DeepgramResults(val channels: List<DeepgramChannel> = emptyList())

@Serializable
data class DeepgramChannel(
    val alternatives: List<DeepgramAlternative> = emptyList(),
    @SerialName("detected_language") val detectedLanguage: String? = null
)

@Serializable
data class DeepgramAlternative(
    val transcript: String? = null,
    val words: List<DeepgramWord> = emptyList()
)

@Serializable
data class DeepgramWord(
    val word: String,
    val start: Double,
    val end: Double,
    val confidence: Double? = null,
    @SerialName("punctuated_word") val punctuatedWord: String? = null
)

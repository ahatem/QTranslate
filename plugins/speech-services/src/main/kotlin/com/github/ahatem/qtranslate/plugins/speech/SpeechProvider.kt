package com.github.ahatem.qtranslate.plugins.speech

/** The wire format a provider speaks. */
enum class SpeechProtocol {
    /** `POST {baseUrl}/audio/transcriptions` as multipart — OpenAI, Groq and every Whisper server. */
    OPENAI_COMPATIBLE,

    /** `POST {baseUrl}/listen` with the audio as the body — Deepgram. */
    DEEPGRAM,

    /**
     * `POST {baseUrl}/chat/completions` with the audio as an `input_audio` part, asking the model
     * to transcribe it. For multimodal chat models (Gemini, gpt-4o-audio) behind an
     * OpenAI-compatible gateway, which offer no transcription endpoint. No word timings.
     */
    CHAT_AUDIO
}

/**
 * A speech-to-text provider the user can pick, with the address and model it uses unless the
 * user overrides them. Leaving the URL or model blank in settings means "the provider's default",
 * so switching provider never leaves a stale address behind.
 */
enum class SpeechProvider(
    val label: String,
    val protocol: SpeechProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Whether the provider refuses requests without a key. Local servers usually don't. */
    val requiresKey: Boolean,
    val keyHint: String,
    val supportsWordTimings: Boolean = true,
    val knownModels: List<String> = emptyList()
) {
    /** No accurate recognizer: the live text is the transcript. Nothing is sent anywhere. */
    NONE(
        "None (live text only)", SpeechProtocol.OPENAI_COMPATIBLE,
        "", "", false,
        "not needed",
        supportsWordTimings = false,
        knownModels = emptyList()
    ),
    GROQ(
        "Groq", SpeechProtocol.OPENAI_COMPATIBLE,
        "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", true,
        "console.groq.com > API Keys",
        supportsWordTimings = true,
        knownModels = listOf("whisper-large-v3-turbo", "whisper-large-v3")
    ),
    OPENAI(
        "OpenAI", SpeechProtocol.OPENAI_COMPATIBLE,
        "https://api.openai.com/v1", "whisper-1", true,
        "platform.openai.com > API keys",
        supportsWordTimings = true,
        knownModels = listOf("whisper-1", "gpt-transcribe")
    ),
    DEEPGRAM(
        "Deepgram", SpeechProtocol.DEEPGRAM,
        "https://api.deepgram.com/v1", "nova-3", true,
        "console.deepgram.com > API Keys",
        supportsWordTimings = true,
        knownModels = listOf("nova-3", "nova-2", "enhanced", "base")
    ),
    LOCAL(
        "Local Whisper server", SpeechProtocol.OPENAI_COMPATIBLE,
        "http://localhost:8000/v1", "Systran/faster-whisper-large-v3", false,
        "usually none",
        supportsWordTimings = true,
        knownModels = listOf("Systran/faster-whisper-large-v3", "Systran/faster-whisper-medium", "whisper-large-v3")
    ),
    CUSTOM(
        "Custom (OpenAI-compatible)", SpeechProtocol.OPENAI_COMPATIBLE,
        "", "", false,
        "whatever the server expects",
        supportsWordTimings = true,
        knownModels = listOf("whisper-1", "whisper-large-v3")
    ),
    CHAT_MODEL(
        "Audio chat model (OpenAI-compatible)", SpeechProtocol.CHAT_AUDIO,
        "", "", false,
        "whatever the gateway expects",
        supportsWordTimings = false,
        knownModels = listOf("gpt-audio-1.5")
    );

    companion object {
        /** All distinct suggested models across providers for editable dropdown default options. */
        val ALL_SUGGESTED_MODELS: List<String> = entries.flatMap { it.knownModels }.distinct()
        val ALL_SUGGESTED_MODELS_STRING: String = ALL_SUGGESTED_MODELS.joinToString(",")

        /** The `options` string of the provider dropdown, in display order. */
        const val DROPDOWN_OPTIONS =
            "None (live text only),Groq,OpenAI,Deepgram,Local Whisper server,Custom (OpenAI-compatible),Audio chat model (OpenAI-compatible)"

        fun fromLabel(label: String?): SpeechProvider =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: NONE
    }
}

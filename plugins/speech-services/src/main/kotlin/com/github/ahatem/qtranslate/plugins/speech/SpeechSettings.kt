package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingGroup
import com.github.ahatem.qtranslate.api.settings.SettingGroups
import com.github.ahatem.qtranslate.api.settings.SettingType
import java.net.URI

const val LIVE_OFF = "Off"
const val LIVE_VOSK_BUILTIN = "Vosk (built-in)"
const val LIVE_DEEPGRAM = "Deepgram (live)"
const val LIVE_OPENAI = "OpenAI Realtime"
const val LIVE_ENGINE_OPTIONS = "$LIVE_OFF,$LIVE_VOSK_BUILTIN,$LIVE_DEEPGRAM,$LIVE_OPENAI"

const val MODE_LOCAL = "Local only"
const val MODE_CLOUD = "Cloud only"
const val MODE_HYBRID = "Hybrid"
const val MODE_OPTIONS = "$MODE_LOCAL,$MODE_CLOUD,$MODE_HYBRID"

enum class SpeechMode(val label: String) {
    LOCAL(MODE_LOCAL),
    CLOUD(MODE_CLOUD),
    HYBRID(MODE_HYBRID);

    companion object {
        fun fromLabel(label: String?): SpeechMode = when (label?.trim()?.lowercase()) {
            "local", "local only" -> LOCAL
            "cloud", "cloud only" -> CLOUD
            else -> HYBRID
        }
    }
}

/**
 * Two jobs, two groups. Live text appears while people talk (Vosk, Deepgram live, OpenAI Realtime);
 * accurate text replaces each finished line (a Whisper-style provider). Either can be off; with both on, the live
 * words show at once and the accurate ones take their place.
 */
@SettingGroups(
    SettingGroup(
        key = "live", title = "Live text", order = 10,
        description = "Words appear while people are still talking, locally via built-in Vosk or cloud streaming (Deepgram, OpenAI Realtime)."
    ),
    SettingGroup(
        key = "accurate", title = "Accurate text", order = 20,
        description = "Each finished line is transcribed again, more accurately, and replaces the live text."
    )
)
data class SpeechSettings(
    @field:Setting(
        label = "Mode",
        description = "Local only uses offline Vosk with zero data leaving your machine. " +
            "Cloud only transcribes through your speech provider. " +
            "Hybrid shows live text instantly then refines each finished line with the accurate provider.",
        type = SettingType.DROPDOWN,
        options = MODE_OPTIONS,
        defaultValue = MODE_HYBRID,
        order = 1
    )
    var mode: String = MODE_HYBRID,

    @field:Setting(
        label = "Provider",
        description = "None keeps only the live text, with nothing sent outside your network. " +
            "Groq is fast and has a free tier. A local Whisper server keeps audio on your computer. " +
            "Custom works with any server that offers an OpenAI-style /audio/transcriptions endpoint. " +
            "Audio chat model sends the audio to a multimodal chat model (Gemini, gpt-4o-audio) through " +
            "an OpenAI-compatible gateway's /chat/completions.",
        type = SettingType.DROPDOWN,
        options = SpeechProvider.DROPDOWN_OPTIONS,
        defaultValue = "Groq",
        group = "accurate",
        showIf = "mode=!Local only",
        order = 10
    )
    var provider: String = SpeechProvider.GROQ.label,

    @field:Setting(
        label = "Server URL",
        description = "Leave blank for the provider's own address. Required for Custom and Audio chat model.",
        type = SettingType.TEXT,
        group = "accurate",
        showIf = "provider=Custom (OpenAI-compatible),Audio chat model (OpenAI-compatible),Local Whisper server",
        order = 20
    )
    var baseUrl: String = "",

    @field:Setting(
        label = "API Key",
        description = "Required by Groq, OpenAI and Deepgram. Usually left blank for a local server.",
        type = SettingType.PASSWORD,
        group = "accurate",
        showIf = "provider=Groq,OpenAI,Deepgram,Custom (OpenAI-compatible),Audio chat model (OpenAI-compatible)",
        order = 30
    )
    var apiKey: String = "",

    @field:Setting(
        label = "Model",
        description = "Leave blank for the provider's default (Groq: whisper-large-v3-turbo, OpenAI: whisper-1, " +
            "Deepgram: nova-3). Required for Custom and Audio chat model.",
        type = SettingType.DROPDOWN,
        editable = true,
        options = "whisper-large-v3-turbo,whisper-large-v3,whisper-1,gpt-transcribe,nova-3,nova-2,gpt-audio-1.5",
        group = "accurate",
        showIf = "provider=Groq,OpenAI,Deepgram,Custom (OpenAI-compatible),Audio chat model (OpenAI-compatible),Local Whisper server",
        order = 40
    )
    var model: String = "",

    @field:Setting(
        label = "Live engine",
        description = "Off records and transcribes line by line. Vosk runs locally. Deepgram live and OpenAI Realtime provide cloud streaming.",
        type = SettingType.DROPDOWN,
        options = LIVE_ENGINE_OPTIONS,
        defaultValue = LIVE_OFF,
        group = "live",
        showIf = "mode=!Cloud only",
        order = 5
    )
    var liveEngine: String = LIVE_OFF,

    @field:Setting(
        label = "Built-in model",
        description = "Model for built-in Vosk engine. Click 'Download Model' to fetch it from alphacephei.com.",
        editable = true,
        options = VoskModelManager.DROPDOWN_OPTIONS,
        defaultValue = VoskModelManager.DEFAULT_MODEL_NAME,
        group = "live",
        showIf = "liveEngine=$LIVE_VOSK_BUILTIN",
        order = 40
    )
    var voskBuiltinModel: String = VoskModelManager.DEFAULT_MODEL_NAME,

    @field:Setting(
        label = "Live Model",
        description = "Streaming transcription model for OpenAI Realtime (default: gpt-live-transcribe) or Deepgram live (default: nova-3).",
        type = SettingType.TEXT,
        group = "live",
        showIf = "liveEngine=$LIVE_DEEPGRAM,$LIVE_OPENAI",
        order = 60
    )
    var liveModel: String = "",

    @field:Setting(
        label = "Live API Key",
        description = "API key for Deepgram live or OpenAI Realtime. If left blank, uses the API Key from above.",
        type = SettingType.PASSWORD,
        group = "live",
        showIf = "liveEngine=$LIVE_DEEPGRAM,$LIVE_OPENAI",
        order = 70
    )
    var liveApiKey: String = ""
) : PluginSettings.Configurable() {

    @Transient
    var modelManager: VoskModelManager? = null

    @Transient
    var onModelSelectionApplied: ((String) -> Unit)? = null

    @PluginAction(
        label = "Download Model",
        group = "live",
        order = 45,
        tooltip = "Downloads and installs the selected Vosk model from alphacephei.com"
    )
    fun downloadSelectedModel(): String? {
        val manager = modelManager ?: return "Model manager not initialized."
        val result = manager.downloadAction(voskBuiltinModel)
        if (manager.isInstalled(voskBuiltinModel)) {
            onModelSelectionApplied?.invoke(voskBuiltinModel)
        }
        return result
    }

    fun downloadSelectedModelVisible(): Boolean {
        val manager = modelManager ?: return true
        return !manager.isInstalled(voskBuiltinModel)
    }

    fun voskBuiltinModelDisplayLabel(modelName: String): String {
        val model = VoskModelManager.MODELS_BY_NAME[modelName]
        val installed = modelManager?.isInstalled(modelName) == true
        val baseLabel = if (model != null) {
            model.label
        } else {
            val dir = java.io.File(modelManager?.modelsDirectory, modelName)
            val lang = modelManager?.inferLanguageFromModelDir(dir) ?: "custom"
            "$modelName ($lang)"
        }
        return if (installed) {
            SpeechLocalization.get("installed_marker", baseLabel)
        } else {
            baseLabel
        }
    }

    fun downloadSelectedModelEnabled(): Boolean {
        val manager = modelManager ?: return true
        return !manager.isDownloading(voskBuiltinModel)
    }

    fun downloadSelectedModelLabel(): String {
        val manager = modelManager ?: return SpeechLocalization.get("download_model_button")
        val downloading = manager.currentlyDownloadingModel()
        return if (downloading != null) {
            val label = VoskModelManager.MODELS_BY_NAME[downloading]?.label ?: downloading
            SpeechLocalization.get("downloading_model_label", label)
        } else {
            SpeechLocalization.get("download_model_button")
        }
    }

    @PluginAction(
        label = "Import Model",
        group = "live",
        order = 46,
        tooltip = "Imports an existing Vosk model from a local ZIP archive or directory"
    )
    fun importModel(): String? {
        val manager = modelManager ?: return "Model manager not initialized."
        return manager.importModelInteractive()
    }

    fun importModelVisible(): Boolean = true

    @PluginAction(
        label = "Delete Model",
        group = "live",
        order = 47,
        tooltip = "Deletes the selected Vosk model from local storage"
    )
    fun deleteSelectedModel(): String? {
        val manager = modelManager ?: return "Model manager not initialized."
        return manager.deleteAction(voskBuiltinModel)
    }

    fun deleteSelectedModelVisible(): Boolean {
        val manager = modelManager ?: return false
        return manager.isInstalled(voskBuiltinModel)
    }

    @PluginAction(
        label = "Check Models",
        group = "live",
        order = 48,
        tooltip = "Lists locally downloaded Vosk models"
    )
    fun checkModels(): String? {
        val manager = modelManager ?: return "Model manager not initialized."
        return manager.statusAction(voskBuiltinModel)
    }

    val speechMode: SpeechMode get() = SpeechMode.fromLabel(mode)

    val speechProvider: SpeechProvider
        get() = if (speechMode == SpeechMode.LOCAL) SpeechProvider.NONE else SpeechProvider.fromLabel(provider)

    val effectiveLiveApiKey: String
        get() {
            val key = liveApiKey.trim()
            if (key.isNotEmpty()) return key
            // P5: only fall back to accurate apiKey if vendors match
            val accurateMatchesVendor = when (liveEngine) {
                LIVE_OPENAI -> speechProvider == SpeechProvider.OPENAI
                LIVE_DEEPGRAM -> speechProvider == SpeechProvider.DEEPGRAM
                else -> false
            }
            return if (accurateMatchesVendor) apiKey.trim() else ""
        }

    val liveEnabled: Boolean
        get() = when (speechMode) {
            SpeechMode.CLOUD -> false
            SpeechMode.LOCAL -> liveEngine == LIVE_VOSK_BUILTIN
            SpeechMode.HYBRID -> when (liveEngine) {
                LIVE_VOSK_BUILTIN -> true
                LIVE_DEEPGRAM -> effectiveLiveApiKey.isNotBlank()
                LIVE_OPENAI -> effectiveLiveApiKey.isNotBlank()
                else -> false
            }
        }

    /** The address requests go to: the user's, or the provider's when left blank. */
    val effectiveBaseUrl: String get() = baseUrl.trim().trimEnd('/').ifEmpty { speechProvider.defaultBaseUrl }

    val effectiveModel: String get() = model.trim().ifEmpty { speechProvider.defaultModel }

    /** What is wrong with the address, or null when it is usable. */
    fun urlError(): ServiceError? {
        if (speechMode == SpeechMode.LOCAL || speechProvider == SpeechProvider.NONE) return null
        val url = effectiveBaseUrl
        if (url.isEmpty()) {
            return ServiceError.ConfigurationError(
                "Speech Recognition needs a Server URL for ${speechProvider.label}. Set it in Settings > Plugins > Speech Recognition."
            )
        }
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return ServiceError.ConfigurationError("Speech Recognition Server URL is not a valid http(s) address: $url")
        }
        return null
    }

    /** Why requests would fail before any is sent, or null when the settings are usable. */
    fun configurationError(): ServiceError? {
        if (speechProvider == SpeechProvider.NONE) {
            return ServiceError.ConfigurationError(
                "No accurate speech provider is selected (live text only). Choose one in Settings > Plugins > Speech Recognition."
            )
        }
        urlError()?.let { return it }
        // P9: Custom and Audio chat model require a model name
        if (effectiveModel.isEmpty() || ((speechProvider == SpeechProvider.CUSTOM || speechProvider == SpeechProvider.CHAT_MODEL) && model.isBlank())) {
            return ServiceError.ConfigurationError(
                "Speech Recognition needs a Model for ${speechProvider.label}. Set it in Settings > Plugins > Speech Recognition."
            )
        }
        val host = URI(effectiveBaseUrl).host
        if (speechProvider.requiresKey && apiKey.isBlank() && !isLocal(host)) {
            return ServiceError.ConfigurationError(
                "Speech Recognition has no API key for ${speechProvider.label} (${speechProvider.keyHint}). " +
                    "Add it in Settings > Plugins > Speech Recognition."
            )
        }
        return null
    }

    private fun isLocal(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]").lowercase()
        return clean == "localhost" || clean == "127.0.0.1" || clean == "::1" ||
            clean.endsWith(".local") || clean.startsWith("192.168.") || clean.startsWith("10.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(clean) ||
            clean.startsWith("fc00:") || clean.startsWith("fd") || clean == "::"
    }
}

package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse
import com.github.ahatem.qtranslate.api.speech.SpeechRecognizer
import com.github.ahatem.qtranslate.api.speech.VerificationCapability
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Speech-to-text for Conversation Sessions, through whichever provider the user picks: a cloud
 * service (Groq, OpenAI, Deepgram), a local Whisper server, or any OpenAI-compatible endpoint.
 *
 * Kept apart from the AI Services plugin on purpose. Transcription has its own providers, keys
 * and models, and someone who only wants speech-to-text should find it under its own name.
 */
class SpeechPlugin : Plugin<SpeechSettings> {
    private lateinit var context: PluginContext
    private val http: HttpClient get() = context.http
    private var settings = SpeechSettings()
    private var services: List<Service> = emptyList()
    private lateinit var modelManager: VoskModelManager
    private var builtinService: VoskBuiltinService? = null
    private var pluginScope: CoroutineScope? = null

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        modelManager = VoskModelManager({ context.getPluginDataDirectory() })
        builtinService = VoskBuiltinService(modelManager, { settings })
        val rawProvider = context.settings.getString(KEY_PROVIDER) ?: SpeechProvider.GROQ.label
        val rawLiveEngine = context.settings.getString(KEY_LIVE_ENGINE)
            ?.let { if (it == "Vosk (local server)") LIVE_VOSK_BUILTIN else it }
            ?: LIVE_OFF
        val rawMode = context.settings.getString(KEY_MODE)
        val resolvedMode = migrateSpeechMode(rawMode, rawLiveEngine, rawProvider)

        settings = SpeechSettings(
            mode = resolvedMode,
            provider = rawProvider,
            baseUrl = context.settings.getString(KEY_BASE_URL).orEmpty(),
            apiKey = context.secrets.get(KEY_API_KEY).orEmpty(),
            model = context.settings.getString(KEY_MODEL).orEmpty(),
            liveEngine = if (resolvedMode == MODE_LOCAL && rawLiveEngine == LIVE_OFF) LIVE_VOSK_BUILTIN else rawLiveEngine,
            voskBuiltinModel = context.settings.getString(KEY_VOSK_BUILTIN_MODEL) ?: VoskModelManager.defaultModelForSystem(),
            liveModel = context.settings.getString(KEY_LIVE_MODEL).orEmpty(),
            liveApiKey = context.secrets.get(KEY_LIVE_API_KEY).orEmpty()
        ).apply {
            this.modelManager = this@SpeechPlugin.modelManager
            this.onModelSelectionApplied = { appliedModel ->
                pluginScope?.launch {
                    context.settings.put(KEY_VOSK_BUILTIN_MODEL, appliedModel)
                }
                this@SpeechPlugin.settings.voskBuiltinModel = appliedModel
            }
        }
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        val builtin = builtinService ?: VoskBuiltinService(modelManager, { settings }).also { builtinService = it }
        val deepgramLive = DeepgramLiveService { settings }
        val openAiRealtime = OpenAiRealtimeService { settings }
        services = listOf(
            SpeechRecognitionService(SpeechClient(context, http) { settings }, builtin) { settings },
            VoskLiveService({ settings }, builtin, deepgramLive, openAiRealtime)
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { pluginScope = it }
        // D1: Only preload if not in Cloud only mode AND liveEngine is built-in Vosk
        if (settings.mode != MODE_CLOUD && settings.liveEngine == LIVE_VOSK_BUILTIN) {
            scope.launch {
                builtin.preloadModel(settings.voskBuiltinModel)
            }
        }
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: SpeechSettings): Result<Unit, ServiceError> {
        val cleaned = settings.copy(
            mode = if (settings.mode in listOf(MODE_LOCAL, MODE_CLOUD, MODE_HYBRID)) settings.mode else MODE_HYBRID,
            provider = SpeechProvider.fromLabel(settings.provider).label,
            baseUrl = settings.baseUrl.trim().trimEnd('/'),
            apiKey = settings.apiKey.trim(),
            model = settings.model.trim(),
            liveEngine = if (settings.liveEngine in listOf(LIVE_VOSK_BUILTIN, LIVE_DEEPGRAM, LIVE_OPENAI)) settings.liveEngine else LIVE_OFF,
            voskBuiltinModel = settings.voskBuiltinModel.trim().ifEmpty { VoskModelManager.DEFAULT_MODEL_NAME },
            liveModel = settings.liveModel.trim(),
            liveApiKey = settings.liveApiKey.trim()
        ).apply {
            this.modelManager = this@SpeechPlugin.modelManager
            this.onModelSelectionApplied = { appliedModel ->
                pluginScope?.launch {
                    context.settings.put(KEY_VOSK_BUILTIN_MODEL, appliedModel)
                }
                this@SpeechPlugin.settings.voskBuiltinModel = appliedModel
            }
        }
        // A missing key is fine to save (the user may add it later); an unusable URL is not.
        cleaned.urlError()?.let { return Err(ServiceError.ValidationError(it.message)) }

        val oldModel = this.settings.voskBuiltinModel
        context.settings.put(KEY_MODE, cleaned.mode)
        context.settings.put(KEY_PROVIDER, cleaned.provider)
        context.settings.put(KEY_BASE_URL, cleaned.baseUrl)
        context.settings.put(KEY_MODEL, cleaned.model)
        context.settings.put(KEY_LIVE_ENGINE, cleaned.liveEngine)
        context.settings.put(KEY_VOSK_BUILTIN_MODEL, cleaned.voskBuiltinModel)
        context.settings.put(KEY_LIVE_MODEL, cleaned.liveModel)
        context.secrets.put(KEY_API_KEY, cleaned.apiKey)
        context.secrets.put(KEY_LIVE_API_KEY, cleaned.liveApiKey)
        this.settings = cleaned

        if (cleaned.voskBuiltinModel != oldModel) {
            builtinService?.onSelectedModelChanged(cleaned.voskBuiltinModel)
            if (cleaned.mode != MODE_CLOUD && cleaned.liveEngine == LIVE_VOSK_BUILTIN) {
                pluginScope?.launch {
                    builtinService?.preloadModel(cleaned.voskBuiltinModel)
                }
            }
        }
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        pluginScope?.cancel()
        pluginScope = null
        builtinService?.close()
        services = emptyList()
    }

    override suspend fun shutdown() {
        pluginScope?.cancel()
        pluginScope = null
        builtinService?.close()
        services = emptyList()
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): SpeechSettings = settings

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_API_KEY = "apiKey"
        const val KEY_MODEL = "model"
        const val KEY_LIVE_ENGINE = "liveEngine"
        const val KEY_VOSK_BUILTIN_MODEL = "voskBuiltinModel"
        const val KEY_LIVE_MODEL = "liveModel"
        const val KEY_LIVE_API_KEY = "liveApiKey"

        fun migrateSpeechMode(savedMode: String?, liveEngine: String, provider: String): String {
            if (!savedMode.isNullOrBlank() && savedMode in listOf(MODE_LOCAL, MODE_CLOUD, MODE_HYBRID)) {
                return savedMode
            }
            val isVosk = liveEngine == LIVE_VOSK_BUILTIN || liveEngine == "Vosk (local server)"
            val hasAccurate = provider.isNotBlank() && provider != SpeechProvider.NONE.label
            return when {
                isVosk && hasAccurate -> MODE_HYBRID
                isVosk && !hasAccurate -> MODE_LOCAL
                liveEngine == LIVE_OFF -> MODE_CLOUD
                hasAccurate -> MODE_HYBRID
                else -> MODE_LOCAL
            }
        }
    }
}

class SpeechRecognitionService(
    private val client: SpeechClient,
    private val builtinLive: VoskBuiltinService? = null,
    private val settings: () -> SpeechSettings
) : SpeechRecognizer, VerificationCapability {
    override val metadata: ServiceMetadata get() = ServiceMetadata(requiresConfiguration = true)
    override val isIndependentVerifier: Boolean
        get() {
            val current = settings()
            return current.mode != MODE_LOCAL && current.speechProvider != SpeechProvider.NONE
        }
    override val key: String = "speech"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/speech.svg"
    override val supportedLanguages: SupportedLanguages
        get() {
            val current = settings()
            if (current.mode == MODE_LOCAL || current.speechProvider == SpeechProvider.NONE) {
                return builtinLive?.supportedLanguages ?: SupportedLanguages.All
            }
            return SupportedLanguages.All
        }

    /** Names the provider, so the Services list and the conversation window say what is in use. */
    override val name: String
        get() {
            val current = settings()
            return when {
                current.mode == MODE_LOCAL || current.speechProvider == SpeechProvider.NONE ->
                    "Speech Recognition (Vosk offline)"
                else -> "Speech Recognition (${current.speechProvider.label})"
            }
        }

    override suspend fun transcribe(request: SpeechRecognitionRequest): Result<SpeechRecognitionResponse, ServiceError> {
        val current = settings()
        if (current.mode == MODE_LOCAL || current.speechProvider == SpeechProvider.NONE) {
            val builtin = builtinLive
                ?: return Err(ServiceError.ConfigurationError("Local offline Vosk speech recognizer is not available."))
            val rawBytes = request.audio.bytes
            val pcm = if (rawBytes.size > 44 && rawBytes[0] == 'R'.code.toByte() && rawBytes[1] == 'I'.code.toByte()) {
                rawBytes.copyOfRange(44, rawBytes.size)
            } else rawBytes
            return builtin.transcribeClip(pcm, request.language)
        }
        return client.transcribe(request)
    }

    /** Backs the settings dialog's Test button, which applies what is on screen first. */
    override suspend fun validate(): Result<Unit, ServiceError> {
        val current = settings()
        if (current.mode == MODE_LOCAL || current.speechProvider == SpeechProvider.NONE) {
            return builtinLive?.validate() ?: Ok(Unit)
        }
        return client.testConnection()
    }
}

package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.speech.LiveSpeechRecognizer
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.ahatem.qtranslate.api.speech.ModelCatalogueCapability
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * Live speech recognition router service.
 *
 * Routes incoming live speech sessions to the user-selected live engine:
 * in-process built-in Vosk (offline), Deepgram live streaming, or OpenAI Realtime streaming.
 *
 * Note: Keeps key = "vosk-live" for backwards compatibility so saved active-service
 * selections and user configuration continue resolving without migration breakage (R6).
 */
typealias LiveSpeechRouterService = VoskLiveService

class VoskLiveService(
    private val settings: () -> SpeechSettings,
    private val builtinService: LiveSpeechRecognizer? = null,
    private val deepgramService: LiveSpeechRecognizer? = null,
    private val openAiService: LiveSpeechRecognizer? = null
) : LiveSpeechRecognizer, ModelCatalogueCapability {
    constructor(settings: () -> SpeechSettings) : this(settings, null, null, null)

    override val metadata: ServiceMetadata get() = ServiceMetadata(requiresConfiguration = true)
    override val catalogueLanguages: Set<LanguageCode>
        get() = (builtinService as? ModelCatalogueCapability)?.catalogueLanguages ?: emptySet()

    override fun isModelInstalled(language: LanguageCode): Boolean =
        (builtinService as? ModelCatalogueCapability)?.isModelInstalled(language) ?: true
    override val key: String = "vosk-live"
    override val name: String
        get() = when (settings().liveEngine) {
            LIVE_VOSK_BUILTIN -> "Vosk (built-in)"
            LIVE_DEEPGRAM -> "Deepgram (live)"
            LIVE_OPENAI -> "OpenAI Realtime"
            else -> "Vosk (live)"
        }
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/speech.svg"
    override val supportedLanguages: SupportedLanguages
        get() = when (settings().liveEngine) {
            LIVE_VOSK_BUILTIN -> builtinService?.supportedLanguages ?: SupportedLanguages.Dynamic
            else -> SupportedLanguages.All
        }

    override suspend fun openSession(language: LanguageCode): Result<LiveSpeechSession, ServiceError> {
        val current = settings()
        if (current.mode == MODE_CLOUD) {
            return Err(ServiceError.ConfigurationError("Live text is off in Cloud only mode.", null, ServiceError.ConfigurationError.Reason.FEATURE_DISABLED))
        }
        if (current.mode == MODE_LOCAL && current.liveEngine != LIVE_VOSK_BUILTIN) {
            return Err(ServiceError.ConfigurationError("Only local engines are allowed in Local only mode.", null, ServiceError.ConfigurationError.Reason.FEATURE_DISABLED))
        }
        return when (current.liveEngine) {
            LIVE_VOSK_BUILTIN -> {
                val builtin = builtinService
                    ?: return Err(ServiceError.ConfigurationError("Built-in Vosk engine is not available.", null, ServiceError.ConfigurationError.Reason.FEATURE_DISABLED))
                builtin.openSession(language)
            }
            LIVE_DEEPGRAM -> {
                val deepgram = deepgramService
                    ?: return Err(ServiceError.ConfigurationError("Deepgram live engine is not available.", null, ServiceError.ConfigurationError.Reason.FEATURE_DISABLED))
                deepgram.openSession(language)
            }
            LIVE_OPENAI -> {
                val openAi = openAiService
                    ?: return Err(ServiceError.ConfigurationError("OpenAI Realtime engine is not available.", null, ServiceError.ConfigurationError.Reason.FEATURE_DISABLED))
                openAi.openSession(language)
            }
            else -> Err(ServiceError.ConfigurationError("Live text is off in Settings > Plugins > Speech Recognition.", null, ServiceError.ConfigurationError.Reason.FEATURE_DISABLED))
        }
    }

    override suspend fun validate(): Result<Unit, ServiceError> {
        val current = settings()
        if (current.mode == MODE_CLOUD) return Ok(Unit)
        if (current.mode == MODE_LOCAL && current.liveEngine != LIVE_VOSK_BUILTIN) {
            return Err(ServiceError.ConfigurationError("Only local engines are allowed in Local only mode."))
        }
        return when (current.liveEngine) {
            LIVE_VOSK_BUILTIN -> builtinService?.validate() ?: Ok(Unit)
            LIVE_DEEPGRAM -> deepgramService?.validate() ?: Ok(Unit)
            LIVE_OPENAI -> openAiService?.validate() ?: Ok(Unit)
            else -> Ok(Unit)
        }
    }
}


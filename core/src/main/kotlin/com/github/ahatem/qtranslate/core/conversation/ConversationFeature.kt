package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionary
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.speech.LiveSpeechRecognizer
import com.github.ahatem.qtranslate.api.speech.SpeechRecognizer
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateStringUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the Conversation Sessions window needs, assembled once.
 *
 * Reuses the app's own services throughout: transcription through the active speech recognizer,
 * hover translation through the active translator ([TranslateStringUseCase]) and meanings through
 * the active dictionary, so a change in settings applies here too.
 */
class ConversationFeature(
    scope: CoroutineScope,
    private val activeServiceManager: ActiveServiceManager,
    translateString: TranslateStringUseCase,
    appData: File,
    loggerFactory: LoggerFactory,
    val microphoneBackend: AudioBackend = JavaSoundAudioBackend(),
    val systemBackend: WasapiLoopbackBackend = WasapiLoopbackBackend(),
    val clock: () -> Long = System::currentTimeMillis
) {
    val repository = SessionRepository(File(appData, "conversations"))

    init {
        // Built once at startup, before anything can record: any session still marked as
        // recording was cut off by a crash or power loss last time.
        scope.launch(Dispatchers.IO) { runCatching { repository.recoverInterrupted() } }
    }

    val player = SegmentPlayer()

    @Volatile
    private var playbackActiveUntil = 0L

    fun markPlayback(durationMillis: Long) {
        val now = clock()
        playbackActiveUntil = maxOf(playbackActiveUntil, now + durationMillis + PLAYBACK_COOLDOWN_MILLIS)
    }

    fun stop() {
        player.stop()
        playbackActiveUntil = clock() + PLAYBACK_COOLDOWN_MILLIS
    }

    fun isPlaybackActive(): Boolean {
        if (player.playing.value != null) {
            playbackActiveUntil = maxOf(playbackActiveUntil, clock() + PLAYBACK_COOLDOWN_MILLIS)
            return true
        }
        return clock() < playbackActiveUntil
    }

    val recorder = ConversationRecorder(
        scope = scope,
        repository = repository,
        microphoneBackend = microphoneBackend,
        // Off Windows there is no loopback; a virtual input device (BlackHole, a PulseAudio
        // monitor) is the way in, and it records like a microphone.
        systemBackend = if (systemBackend.isSupported) systemBackend else microphoneBackend,
        recognize = { request ->
            activeServiceManager.getActiveService<SpeechRecognizer>(ServiceRole.SPEECH_RECOGNIZER)?.transcribe(request)
        },
        logger = loggerFactory.getLogger("ConversationRecorder"),
        openLive = { language ->
            activeServiceManager.getActiveService<LiveSpeechRecognizer>(ServiceRole.LIVE_SPEECH_RECOGNIZER)?.openSession(language)
        },
        isPlaybackActive = ::isPlaybackActive,
        isIndependentVerifier = {
            val service = activeServiceManager.getActiveService<SpeechRecognizer>(ServiceRole.SPEECH_RECOGNIZER)
            (service as? com.github.ahatem.qtranslate.api.speech.VerificationCapability)?.isIndependentVerifier ?: true
        }
    )

    val translations = TranslationCache(scope, { text, target -> translateString(text, target) })

    private val preferencesFile = File(repository.root, "preferences.json")

    var preferences: ConversationPreferences
        get() = ConversationPreferences.load(preferencesFile)
        set(value) = ConversationPreferences.save(preferencesFile, value)

    /** Whether a speech service is active, so the window can say so before recording starts. */
    val hasSpeechService: Boolean
        get() = speechServiceName != null || liveServiceName != null

    /** The active speech service's name, e.g. "Speech Recognition (Groq)", or null when none is. */
    val speechServiceName: String?
        get() = activeServiceManager.getActiveService<SpeechRecognizer>(ServiceRole.SPEECH_RECOGNIZER)?.name
            ?.takeUnless {
                val service = activeServiceManager.getActiveService<SpeechRecognizer>(ServiceRole.SPEECH_RECOGNIZER)
                val isIndependent = (service as? com.github.ahatem.qtranslate.api.speech.VerificationCapability)?.isIndependentVerifier ?: true
                !isIndependent
            }

    /** The active live recognizer's name, or null. Whether it is actually set up shows when recording starts. */
    val liveServiceName: String?
        get() = activeServiceManager.getActiveService<LiveSpeechRecognizer>(ServiceRole.LIVE_SPEECH_RECOGNIZER)?.name

    /** Languages supported by the active speech recognizer (e.g. only installed Vosk models in local mode). */
    fun speechLanguages(): List<LanguageCode>? {
        val liveService = activeServiceManager.getActiveService<LiveSpeechRecognizer>(ServiceRole.LIVE_SPEECH_RECOGNIZER)
        val speechService = activeServiceManager.getActiveService<SpeechRecognizer>(ServiceRole.SPEECH_RECOGNIZER)
        val liveLangs = when (val sup = liveService?.supportedLanguages) {
            is com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.Specific -> sup.languages.toList()
            else -> null
        }
        val batchLangs = when (val sup = speechService?.supportedLanguages) {
            is com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.Specific -> sup.languages.toList()
            else -> null
        }
        return liveLangs ?: batchLangs
    }

    /** Full catalogue of languages supported by the engine, including uninstalled downloadable models. */
    fun catalogueLanguages(): Set<LanguageCode>? {
        val liveService = activeServiceManager.getActiveService<LiveSpeechRecognizer>(ServiceRole.LIVE_SPEECH_RECOGNIZER)
        val speechService = activeServiceManager.getActiveService<SpeechRecognizer>(ServiceRole.SPEECH_RECOGNIZER)
        val liveCat = (liveService as? com.github.ahatem.qtranslate.api.speech.ModelCatalogueCapability)?.catalogueLanguages
        val speechCat = (speechService as? com.github.ahatem.qtranslate.api.speech.ModelCatalogueCapability)?.catalogueLanguages
        return (liveCat ?: speechCat)?.takeIf { it.isNotEmpty() }
    }

    /** Whether an offline model is installed for [language]. */
    fun isModelInstalled(language: LanguageCode): Boolean {
        val liveService = activeServiceManager.getActiveService<LiveSpeechRecognizer>(ServiceRole.LIVE_SPEECH_RECOGNIZER)
        val speechService = activeServiceManager.getActiveService<SpeechRecognizer>(ServiceRole.SPEECH_RECOGNIZER)
        val liveCheck = (liveService as? com.github.ahatem.qtranslate.api.speech.ModelCatalogueCapability)?.isModelInstalled(language)
        val speechCheck = (speechService as? com.github.ahatem.qtranslate.api.speech.ModelCatalogueCapability)?.isModelInstalled(language)
        return (liveCheck ?: speechCheck) ?: true
    }

    fun microphones(): List<AudioDevice> = microphoneBackend.inputDevices()

    /** Outputs to record from; on Windows these are playback devices, captured by loopback. */
    fun systemOutputs(): List<AudioDevice> =
        if (systemBackend.isSupported) systemBackend.inputDevices()
        else microphoneBackend.inputDevices().filter { it.looksLikeSystemAudio }

    /** Applications producing audio on Windows that can be selected directly. */
    fun systemApplications(): List<AudioApplication> =
        if (systemBackend.isSupported) systemBackend.activeApplications()
        else emptyList()

    /** Dictionary meanings of [word], bilingual when the dictionary supports it. */
    suspend fun meanings(word: String, source: LanguageCode, target: LanguageCode): Result<List<DictionaryEntry>, ServiceError> {
        val dictionary = activeServiceManager.getActiveService<Dictionary>(ServiceRole.DICTIONARY)
            ?: return Err(ServiceError.ConfigurationError("No dictionary service is active."))
        val lookupLanguage = source.takeUnless { it == LanguageCode.AUTO } ?: LanguageCode.ENGLISH
        val result = if (dictionary is BilingualDictionary) {
            dictionary.lookupBilingual(BilingualDictionaryRequest(word, lookupLanguage, target))
        } else {
            dictionary.lookup(DictionaryRequest(word, lookupLanguage))
        }
        return result.map { it.entries }
    }

    /** Plays a stretch of [source]'s recording given in session time. False when there is no audio. */
    suspend fun play(session: ConversationSession, source: AudioSource, startMillis: Long, endMillis: Long, tag: Any?): Boolean {
        val stream = session.streams.firstOrNull { it.source == source } ?: return false
        val estimatedDuration = (endMillis - startMillis).coerceAtLeast(0)
        markPlayback(estimatedDuration)
        val pcm = withContext(Dispatchers.IO) {
            WavFiles.readRange(
                repository.audioFile(session.id, source),
                startMillis - stream.offsetMillis,
                endMillis - stream.offsetMillis
            )
        }
        if (pcm.isEmpty()) return false
        val actualDuration = pcm.size * 1000L / (PcmStream.SAMPLE_RATE * 2)
        markPlayback(actualDuration)
        return player.play(pcm, tag)
    }

    fun hasAudio(session: ConversationSession, source: AudioSource): Boolean = repository.hasAudio(session.id, source)

    companion object {
        const val PLAYBACK_COOLDOWN_MILLIS = 1_500L
    }
}

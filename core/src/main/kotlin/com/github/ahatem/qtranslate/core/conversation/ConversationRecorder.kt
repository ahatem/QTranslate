package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class RecorderPhase { IDLE, RECORDING, PAUSED, STOPPING }

enum class StreamState { OFF, CONNECTING, LIVE, FAILED }

/** How one audio source is doing, for the status lights in the window. */
data class StreamStatus(
    val state: StreamState = StreamState.OFF,
    val deviceName: String = "",
    /** Loudness of the latest audio, 0..1. */
    val level: Float = 0f,
    val error: String? = null
)

/** Something the user should hear about. Recording carries on through all of them. */
sealed interface RecorderNotice {
    data object NoSpeechService : RecorderNotice
    data class TranscriptionFailed(val error: ServiceError) : RecorderNotice
    data class DeviceLost(val source: AudioSource, val message: String) : RecorderNotice
    data class SaveFailed(val message: String) : RecorderNotice

    /** The live recognizer dropped out; recording carries on without live text while it reconnects. */
    data class LiveFailed(val source: AudioSource, val message: String) : RecorderNotice

    /** The live recognizer is unconfigured or has no model for the chosen language. */
    data class LiveUnavailable(val source: AudioSource, val message: String) : RecorderNotice

    /** Microphone audio is bleeding into the call track (sidetone / loopback). */
    data object EchoDetected : RecorderNotice
}

/** What the live recognizer currently thinks is being said, not yet settled into a segment. */
data class LiveLine(
    val startMillis: Long,
    val text: String,
    val confirmedText: String = "",
    val confirmedWords: List<TranscriptWord> = emptyList(),
    val verificationState: VerificationState = VerificationState.LIVE,
    val agreement: Double? = null,
    val liveWords: List<TranscriptWord> = emptyList()
) {
    val liveText: String
        get() = when {
            confirmedText.isBlank() -> text
            text.startsWith(confirmedText, ignoreCase = true) -> text.substring(confirmedText.length).trimStart()
            else -> text
        }
}

enum class LiveRecognitionStatus {
    OFF,
    CONNECTED,
    RECONNECTING
}

data class RecorderState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    /** The session being recorded, or the last one recorded once stopped. */
    val session: ConversationSession? = null,
    val elapsedMillis: Long = 0,
    val streams: Map<AudioSource, StreamStatus> = AudioSource.entries.associateWith { StreamStatus() },
    val notice: RecorderNotice? = null,
    /** Words being spoken right now, per source, while a live recognizer is in use. */
    val live: Map<AudioSource, LiveLine> = emptyMap(),
    val liveStatus: LiveRecognitionStatus = LiveRecognitionStatus.OFF
) {
    val isActive: Boolean get() = phase == RecorderPhase.RECORDING || phase == RecorderPhase.PAUSED
}

data class RecordingOptions(
    val title: String,
    val microphone: AudioDevice?,
    val systemOutput: AudioDevice?,
    /** What the user speaks. */
    val microphoneLanguage: LanguageCode = LanguageCode.AUTO,
    /** What the people on the call speak. */
    val systemLanguage: LanguageCode = LanguageCode.AUTO,
    val keepAudio: Boolean = true
)

/**
 * Records a conversation: the microphone and the computer's output as two separate streams, each
 * written to its own file, cut into utterances at pauses and transcribed as it goes.
 *
 * The layers fail independently. A device that drops out stops only its own stream; a speech
 * service that is missing or failing leaves the audio recording and marks the affected segments
 * for a retry; a transcript that cannot be saved never touches the recordings.
 *
 * Time on the session timeline is recorded time: it stands still while paused, so a segment's
 * timestamp is also its position in the stream's file (after the stream's start offset).
 */
class ConversationRecorder(
    private val scope: CoroutineScope,
    private val repository: SessionRepository,
    private val microphoneBackend: AudioBackend,
    private val systemBackend: AudioBackend,
    recognize: Recognize,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The active live recognizer, if any. Without one, speech is cut at pauses and sent as clips. */
    private val openLive: OpenLive = { null },
    /** True while in-app playback is active, so recorded audio is muted to prevent acoustic/loopback feedback. */
    private val isPlaybackActive: () -> Boolean = { false },
    /** Whether the speech recognizer provides independent verification (false in local mode to avoid self-verification). */
    private val isIndependentVerifier: () -> Boolean = { true }
) {
    private val transcriber = SegmentTranscriber(recognize)

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val sessionLock = Mutex()
    private var streams: List<StreamRecorder> = emptyList()
    private var ticker: Job? = null
    private var options: RecordingOptions? = null

    // Recorded-time clock: wall time since start, minus time spent paused.
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L

    @Volatile
    private var paused = false

    private fun recordedMillis(): Long {
        val now = if (paused) pausedAt else clock()
        return (now - startedAt - pausedTotal).coerceAtLeast(0)
    }

    /** Starts a new session. Ignored while one is already running. */
    fun start(options: RecordingOptions) {
        if (_state.value.isActive || _state.value.phase == RecorderPhase.STOPPING) return
        this.options = options

        val session = ConversationSession(
            id = newSessionId(),
            title = options.title,
            createdAt = clock(),
            spokenLanguage = options.microphoneLanguage.tag,
            callLanguage = options.systemLanguage.tag,
            keepAudio = options.keepAudio
        )
        runCatching { repository.save(session) }
            .onFailure { e -> logger.error("Could not create conversation session", e) }

        startedAt = clock()
        pausedTotal = 0
        paused = false

        streams = listOfNotNull(
            options.microphone?.let { StreamRecorder(AudioSource.MICROPHONE, it, microphoneBackend, session.id, options.microphoneLanguage) },
            options.systemOutput?.let { StreamRecorder(AudioSource.SYSTEM, it, systemBackend, session.id, options.systemLanguage) }
        )
        _state.value = RecorderState(
            phase = RecorderPhase.RECORDING,
            session = session,
            streams = AudioSource.entries.associateWith { source ->
                val device = streams.firstOrNull { it.source == source }?.device
                if (device == null) StreamStatus() else StreamStatus(StreamState.CONNECTING, device.name)
            }
        )
        streams.forEach(StreamRecorder::start)
        ticker = scope.launch {
            while (isActive) {
                _state.update { state ->
                    state.copy(
                        elapsedMillis = recordedMillis(),
                        streams = state.streams.mapValues { (source, status) ->
                            streams.firstOrNull { it.source == source }?.let { status.copy(level = it.level) } ?: status
                        }
                    )
                }
                delay(TICK_MILLIS)
            }
        }
        logger.info("Conversation recording started (${streams.joinToString { "${it.source}: ${it.device.name}" }})")
    }

    fun pause() {
        if (_state.value.phase != RecorderPhase.RECORDING) return
        pausedAt = clock()
        paused = true
        _state.update { it.copy(phase = RecorderPhase.PAUSED, elapsedMillis = recordedMillis()) }
    }

    fun resume() {
        if (_state.value.phase != RecorderPhase.PAUSED) return
        pausedTotal += clock() - pausedAt
        paused = false
        _state.update { it.copy(phase = RecorderPhase.RECORDING) }
    }

    /**
     * Stops recording, waits up to [transcriptionGraceMillis] for clips still being transcribed
     * and saves the session. Clips that do not finish in time are kept as failed segments, to be
     * retried from the audio.
     */
    suspend fun stop(transcriptionGraceMillis: Long = 30_000) {
        if (!_state.value.isActive) return
        val duration = recordedMillis()
        paused = false
        _state.update { it.copy(phase = RecorderPhase.STOPPING, elapsedMillis = duration) }
        ticker?.cancel()

        streams.forEach(StreamRecorder::stopCapture)
        streams.map { it.captureJob }.filterNotNull().joinAll()
        withTimeoutOrNull(transcriptionGraceMillis) { streams.mapNotNull { it.transcriptionJob }.joinAll() }
        streams.forEach { it.transcriptionJob?.cancel() }

        val keepAudio = options?.keepAudio != false
        updateSession { session ->
            session.copy(
                status = SessionStatus.COMPLETED,
                durationMillis = duration,
                segments = session.segments.map { if (it.pending) it.copy(pending = false, failed = true) else it }
            )
        }
        val session = _state.value.session
        if (!keepAudio && session != null) {
            withContext(Dispatchers.IO) { repository.deleteAudio(session.id) }
        }
        streams = emptyList()
        _state.update { it.copy(phase = RecorderPhase.IDLE, streams = AudioSource.entries.associateWith { StreamStatus() }, live = emptyMap(), liveStatus = LiveRecognitionStatus.OFF) }
        logger.info("Conversation recording stopped after ${formatTimestamp(duration)}")
    }

    /** For application exit: stops and saves without waiting for the speech service. */
    fun shutdown() {
        if (!_state.value.isActive) return
        runBlocking { withTimeoutOrNull(SHUTDOWN_MILLIS) { stop(transcriptionGraceMillis = 0) } }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** Shows [session] (a saved one) when nothing is recording. */
    fun show(session: ConversationSession?) {
        if (_state.value.isActive) return
        _state.update { it.copy(session = session) }
    }

    /**
     * Transcribes a failed segment again from its recording. Works on the live session and on
     * saved ones, as long as their audio was kept.
     */
    suspend fun retry(session: ConversationSession, segment: TranscriptSegment): ConversationSession? {
        val stream = session.streams.firstOrNull { it.source == segment.source } ?: return null
        val file = repository.audioFile(session.id, segment.source)
        val pcm = withContext(Dispatchers.IO) {
            WavFiles.readRange(file, segment.startMillis - stream.offsetMillis, segment.endMillis - stream.offsetMillis)
        }
        if (pcm.isEmpty()) return null
        val pendingSegment = segment.copy(pending = true, draft = false, failed = false, text = "")
        if (_state.value.session?.id == session.id) {
            updateSession { it.withSegment(pendingSegment) }
        }
        val outcome = transcriber.transcribe(
            pendingSegment, pcm,
            LanguageCode(session.languageOf(segment.source)), promptBefore(session, segment)
        )
        report(outcome)
        val updated = session.withSegment(outcome.segment)
        if (_state.value.session?.id == session.id) {
            updateSession { it.withSegment(outcome.segment) }
        } else {
            withContext(Dispatchers.IO) { runCatching { repository.save(updated) } }
        }
        return updated
    }

    fun updateLiveSession(transform: (ConversationSession) -> ConversationSession) {
        if (_state.value.isActive) {
            scope.launch { updateSession(transform) }
        }
    }

    @Volatile
    var isMicrophoneMuted: Boolean = false

    @Volatile
    var isSystemMuted: Boolean = false

    fun updateSpokenLanguage(language: LanguageCode) {
        if (options?.microphoneLanguage == language) return
        options = options?.copy(microphoneLanguage = language)
        scope.launch { updateSession { it.copy(spokenLanguage = language.tag) } }
        streams.firstOrNull { it.source == AudioSource.MICROPHONE }?.switchLiveLanguage(language)
    }

    fun updateCallLanguage(language: LanguageCode) {
        if (options?.systemLanguage == language) return
        options = options?.copy(systemLanguage = language)
        scope.launch { updateSession { it.copy(callLanguage = language.tag) } }
        streams.firstOrNull { it.source == AudioSource.SYSTEM }?.switchLiveLanguage(language)
    }

    /**
     * Deletes a single segment from [session] and updates persistence.
     */
    suspend fun deleteSegment(session: ConversationSession, segmentId: String): ConversationSession {
        val updated = session.withoutSegment(segmentId)
        if (_state.value.session?.id == session.id) {
            updateSession { it.withoutSegment(segmentId) }
        } else {
            withContext(Dispatchers.IO) { runCatching { repository.save(updated) } }
        }
        return updated
    }

    /**
     * Retries every failed segment of [session], one after another so a provider's rate limit is
     * not hit all at once. Stops early if the speech service is still missing.
     */
    suspend fun retryFailed(session: ConversationSession): ConversationSession {
        var current = session
        // The notice left over from recording would otherwise end the loop after one segment.
        clearNotice()
        for (segment in session.segments.filter { it.failed }) {
            current = retry(current, segment) ?: continue
            if (_state.value.notice == RecorderNotice.NoSpeechService) break
        }
        return current
    }

    private suspend fun updateSession(transform: (ConversationSession) -> ConversationSession) {
        sessionLock.withLock {
            var updated: ConversationSession? = null
            _state.update { s ->
                val current = s.session ?: return@update s
                val u = transform(current)
                updated = u
                s.copy(session = u)
            }
            val session = updated ?: return
            withContext(Dispatchers.IO) {
                runCatching { repository.save(session) }.onFailure { e ->
                    logger.error("Could not save conversation transcript", e)
                    _state.update { it.copy(notice = RecorderNotice.SaveFailed(e.message ?: e.toString())) }
                }
            }
        }
    }

    private fun applySegment(current: ConversationSession, segment: TranscriptSegment): ConversationSession {
        val existing = current.segments.firstOrNull { it.id == segment.id }
        if (existing != null && existing.endMillis > segment.endMillis) {
            return current
        }

        // If it's on the SYSTEM stream and duplicates recent speech on the MICROPHONE stream, drop it
        if (segment.source == AudioSource.SYSTEM && segment.text.isNotBlank()) {
            if (isEcho(segment, current.segments)) {
                logger.warn("Echo detected: microphone audio bled into call stream ('${segment.text}')")
                _state.update { it.copy(notice = RecorderNotice.EchoDetected) }
                return current.copy(segments = current.segments.filterNot { it.id == segment.id })
            }
        }

        // Both MICROPHONE and SYSTEM streams operate as independent channels without cancelling each other.
        return current.withSegment(segment)
    }

    private fun isEcho(candidate: TranscriptSegment, allSegments: List<TranscriptSegment>): Boolean =
        allSegments.any { other -> isEchoPair(candidate, other) }

    private fun report(outcome: SegmentTranscriber.Outcome) {
        val notice = when (outcome) {
            is SegmentTranscriber.Outcome.Done -> return
            is SegmentTranscriber.Outcome.NoService -> RecorderNotice.NoSpeechService
            is SegmentTranscriber.Outcome.Failed -> {
                logger.warn("Transcription failed: ${outcome.error.message}")
                RecorderNotice.TranscriptionFailed(outcome.error)
            }
        }
        _state.update { it.copy(notice = notice) }
    }

    /** The tail of what the same source said before [segment], as context for the speech service. */
    private fun promptBefore(session: ConversationSession, segment: TranscriptSegment): String? =
        session.segments
            .filter { it.source == segment.source && it.startMillis < segment.startMillis && it.text.isNotBlank() }
            .takeLast(3)
            .joinToString(" ") { it.text }
            .takeIf { it.isNotBlank() }

    private fun setStream(source: AudioSource, transform: (StreamStatus) -> StreamStatus) =
        _state.update { state -> state.copy(streams = state.streams + (source to transform(state.streams.getValue(source)))) }

    private fun updateLiveStatus() {
        val overall = when {
            streams.any { it.liveStatus == LiveRecognitionStatus.RECONNECTING } -> LiveRecognitionStatus.RECONNECTING
            streams.any { it.liveStatus == LiveRecognitionStatus.CONNECTED } -> LiveRecognitionStatus.CONNECTED
            else -> LiveRecognitionStatus.OFF
        }
        _state.update { it.copy(liveStatus = overall) }
    }

    private class BufferEntry(val pcm: ByteArray, val startSample: Long)

    private inner class StreamRecorder(
        val source: AudioSource,
        val device: AudioDevice,
        private val backend: AudioBackend,
        private val sessionId: String,
        initialLanguage: LanguageCode = LanguageCode.AUTO
    ) {
        @Volatile
        var currentLanguage: LanguageCode = initialLanguage
            private set
        private val segmenter = SpeechSegmenter()
        private val utterances = Channel<Pair<TranscriptSegment, ByteArray>>(Channel.UNLIMITED)
        private var writer: WavFileWriter? = null
        private var pcm: PcmStream? = null
        private var offsetMillis: Long? = null

        @Volatile
        private var stopping = false

        // ---- live recognition ----
        @Volatile
        private var live: LiveSpeechSession? = null
        private var liveEvents: Job? = null
        private var liveConnecting: Job? = null
        /** No live recognizer is set up: never try again in this session, and say nothing. */
        @Volatile
        private var liveUnavailable = false
        private var liveFailures = 0
        var liveStatus = LiveRecognitionStatus.OFF
            private set
        private val liveLock = Any()
        private val rollingBuffer = java.util.ArrayDeque<BufferEntry>()
        private var rollingBufferBytes = 0
        @Volatile
        private var samplesSentToLive = 0L
        /** Stream sample at which the current live session started; its event times count from here. */
        private var liveStartSample = 0L
        /** Stream sample where the last settled segment ended. */
        private var lastFinalSample = 0L
        /** Stream sample where the speech now being heard began, once a partial has arrived. */
        private var utteranceStartSample: Long? = null
        /** Where the voice detector heard speech begin since the last settled segment. */
        @Volatile
        private var voicedStartSample: Long? = null
        /** Stream sample up to which audio has already been verified in the ongoing utterance. */
        private var verifiedUpToSample: Long? = null
        /** Text confirmed by rolling audio verification during the ongoing utterance. */
        @Volatile
        private var confirmedText: String = ""
        /** Words confirmed by rolling audio verification during the ongoing utterance. */
        @Volatile
        private var confirmedWords: List<TranscriptWord> = emptyList()
        /** Honest verification state of the ongoing utterance's checked prefix. */
        @Volatile
        private var liveVerificationState: VerificationState = VerificationState.LIVE
        @Volatile
        private var liveAgreement: Double? = null
        private var matchedWordsTotal: Int = 0
        private var liveWordsCheckedTotal: Int = 0
        private var accurateWordsCheckedTotal: Int = 0
        /** In-flight rolling verification job. */
        private var verificationJob: Job? = null

        var captureJob: Job? = null
        var transcriptionJob: Job? = null
        val level: Float get() = if (paused) 0f else segmenter.level

        private val samplesWritten: Long get() = writer?.sampleCount ?: 0

        fun start() {
            captureJob = scope.launch(Dispatchers.IO) { capture() }
            transcriptionJob = scope.launch {
                // Several at once: a slow provider (a chat model takes 20 s or more) would otherwise
                // fall further behind with every line.
                val slots = Semaphore(MAX_CONCURRENT_TRANSCRIPTIONS)
                coroutineScope {
                    for ((placeholder, audio) in utterances) {
                        slots.acquire()
                        launch {
                            try {
                                transcribe(placeholder, audio)
                            } finally {
                                slots.release()
                            }
                        }
                    }
                }
            }
        }

        private suspend fun transcribe(placeholder: TranscriptSegment, audio: ByteArray) {
            val session = _state.value.session ?: return
            val outcome = transcriber.transcribe(
                placeholder, audio, LanguageCode(session.languageOf(source)), promptBefore(session, placeholder)
            )
            if (outcome is SegmentTranscriber.Outcome.Done) {
                updateDetectedLanguage(outcome.segment.language)
            }
            if (placeholder.draft) {
                // Refining a live line: the live text stands unless something better comes back.
                when (outcome) {
                    is SegmentTranscriber.Outcome.Done ->
                        if (outcome.segment.text.isBlank()) {
                            // The accurate recognizer heard no speech where the live one guessed words.
                            updateSession { s -> s.copy(segments = s.segments.filterNot { it.id == placeholder.id }) }
                        } else {
                            val aligned = WordAlignment.align(
                                liveText = placeholder.text,
                                accurateText = outcome.segment.text,
                                accurateWords = outcome.segment.words,
                                defaultStartMillis = placeholder.startMillis,
                                defaultEndMillis = placeholder.endMillis
                            )
                            updateSession {
                                applySegment(
                                    it,
                                    outcome.segment.copy(
                                        text = outcome.segment.text.trim(),
                                        words = aligned.words,
                                        verificationState = aligned.state,
                                        agreement = aligned.agreement
                                    )
                                )
                            }
                        }
                    // Live text alone is a legitimate setup, not an error, but remains honestly UNVERIFIED.
                    is SegmentTranscriber.Outcome.NoService -> updateSession {
                        val unverifiedWords = placeholder.words.map { w ->
                            if (w.verificationState == VerificationState.LIVE) w.copy(verificationState = VerificationState.UNVERIFIED) else w
                        }
                        applySegment(it, placeholder.copy(draft = false, verificationState = VerificationState.UNVERIFIED, words = unverifiedWords))
                    }
                    is SegmentTranscriber.Outcome.Failed -> {
                        // "No accurate provider chosen" is a setup, not a failure, when live text is on.
                        if (outcome.error !is ServiceError.ConfigurationError) report(outcome)
                        updateSession {
                            val unverifiedWords = placeholder.words.map { w ->
                                if (w.verificationState == VerificationState.LIVE) w.copy(verificationState = VerificationState.UNVERIFIED) else w
                            }
                            applySegment(it, placeholder.copy(draft = false, verificationState = VerificationState.UNVERIFIED, words = unverifiedWords))
                        }
                    }
                }
                return
            }
            report(outcome)
            // A clip that held no speech (a cough, a door) comes back blank: drop it.
            if (outcome is SegmentTranscriber.Outcome.Done && outcome.segment.text.isBlank()) {
                updateSession { s -> s.copy(segments = s.segments.filterNot { it.id == placeholder.id }) }
            } else {
                val finalized = when (outcome) {
                    is SegmentTranscriber.Outcome.Done -> outcome.segment.copy(
                        verificationState = VerificationState.VERIFIED,
                        words = outcome.segment.words.map { it.copy(verificationState = VerificationState.VERIFIED) }
                    )
                    else -> outcome.segment.copy(verificationState = VerificationState.UNVERIFIED)
                }
                updateSession { applySegment(it, finalized) }
            }
        }

        private suspend fun capture() {
            try {
                val stream = backend.open(device).also { pcm = it }
                writer = WavFileWriter(repository.audioFile(sessionId, source))
                setStream(source) { it.copy(state = StreamState.LIVE, error = null) }
                connectLive()
                var flushedForPause = false
                while (!stopping) {
                    val chunk = stream.read() ?: break
                    // While paused the device keeps being read, so its buffer never overflows and
                    // nothing stale is replayed on resume, but nothing is kept. The utterance in
                    // progress ends at the pause, and a live session is closed (its server would
                    // otherwise wait mid-sentence) and reopened on resume.
                    if (paused) {
                        if (!flushedForPause) {
                            synchronized(liveLock) {
                                flushUnsentBufferToSegmenter()
                                segmenter.flush()?.let(::announce)
                            }
                            closeLive()
                        }
                        flushedForPause = true
                        continue
                    }
                    if (flushedForPause) connectLive()
                    flushedForPause = false
                    if (chunk.isEmpty()) continue
                    val playing = isPlaybackActive()
                    val isMuted = (source == AudioSource.MICROPHONE && isMicrophoneMuted) ||
                        (source == AudioSource.SYSTEM && isSystemMuted)
                    val effectiveChunk = if (playing || isMuted) ByteArray(chunk.size) else chunk
                    if (playing || isMuted) {
                        segmenter.resetUtterance()
                    }
                    if (offsetMillis == null) {
                        val offset = recordedMillis()
                        offsetMillis = offset
                        updateSession { it.copy(streams = it.streams + SessionStream(source, device.name, offset)) }
                    }
                    val toWrite = if (isMuted) effectiveChunk else chunk
                    writer?.write(toWrite)
                    val chunkCopy = effectiveChunk.copyOf()
                    synchronized(liveLock) {
                        val chunkStartSample = samplesWritten - chunkCopy.size / 2
                        rollingBuffer.addLast(BufferEntry(chunkCopy, chunkStartSample))
                        rollingBufferBytes += chunkCopy.size
                        while (rollingBufferBytes > ROLLING_BUFFER_MAX_BYTES && rollingBuffer.isNotEmpty()) {
                            val trimmed = rollingBuffer.removeFirst()
                            rollingBufferBytes -= trimmed.pcm.size
                            val trimmedEndSample = trimmed.startSample + trimmed.pcm.size / 2
                            if (trimmedEndSample > samplesSentToLive) {
                                val unsentPcm = if (trimmed.startSample >= samplesSentToLive) {
                                    trimmed.pcm
                                } else {
                                    val skipSamples = (samplesSentToLive - trimmed.startSample).toInt()
                                    trimmed.pcm.copyOfRange(skipSamples * 2, trimmed.pcm.size)
                                }
                                segmenter.accept(unsentPcm).forEach(::announce)
                                samplesSentToLive = trimmedEndSample
                            }
                        }

                        val session = live
                        if (session != null) {
                            session.sendAudio(effectiveChunk)
                            samplesSentToLive = samplesWritten
                            // The live recognizer decides where lines end; the voice detector still
                            // knows where speech began, which a late partial cannot tell.
                            val completed = segmenter.accept(effectiveChunk)
                            if (voicedStartSample == null) {
                                voicedStartSample = completed.firstOrNull()?.startSample ?: segmenter.activeStartSample
                            }
                        } else if (liveUnavailable) {
                            segmenter.accept(effectiveChunk).forEach(::announce)
                            samplesSentToLive = samplesWritten
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopping) {
                    val message = e.message ?: e.toString()
                    logger.error("Audio capture failed for $source (${device.name})", e)
                    _state.update { state ->
                        val status = state.streams.getValue(source).copy(state = StreamState.FAILED, error = message, level = 0f)
                        state.copy(streams = state.streams + (source to status), notice = RecorderNotice.DeviceLost(source, message))
                    }
                }
            } finally {
                liveConnecting?.cancel()
                val sessionToClose = synchronized(liveLock) {
                    if (live != null) {
                        live
                    } else {
                        flushUnsentBufferToSegmenter()
                        segmenter.flush()?.let(::announce)
                        null
                    }
                }
                if (sessionToClose != null) closeLive()
                utterances.close()
                runCatching { pcm?.close() }
                runCatching { writer?.close() }
            }
        }

        private var detectedLanguage: String? = null

        private fun updateDetectedLanguage(detected: String?) {
            if (detected.isNullOrBlank() || detected == "auto") return
            val configured = _state.value.session?.languageOf(source) ?: "auto"
            if (configured == "auto" && detected != detectedLanguage) {
                val previous = detectedLanguage
                detectedLanguage = detected
                logger.info("Language detected as '$detected' for $source (was: ${previous ?: "auto"})")
                if (live != null && !stopping && !paused) {
                    scope.launch {
                        closeLive()
                        connectLive()
                    }
                }
            }
        }

        private fun flushUnsentBufferToSegmenter() {
            while (rollingBuffer.isNotEmpty()) {
                val entry = rollingBuffer.removeFirst()
                val entryEndSample = entry.startSample + entry.pcm.size / 2
                if (entryEndSample > samplesSentToLive) {
                    val pcmToAccept = if (entry.startSample >= samplesSentToLive) {
                        entry.pcm
                    } else {
                        val skipSamples = (samplesSentToLive - entry.startSample).toInt()
                        entry.pcm.copyOfRange(skipSamples * 2, entry.pcm.size)
                    }
                    segmenter.accept(pcmToAccept).forEach(::announce)
                    samplesSentToLive = entryEndSample
                }
            }
            rollingBufferBytes = 0
        }

        private fun extractLastBytes(bytesNeeded: Int): List<ByteArray> {
            if (bytesNeeded <= 0 || rollingBuffer.isEmpty()) return emptyList()
            val list = rollingBuffer.toList()
            var accumulated = 0
            var startIndex = list.size
            for (i in list.indices.reversed()) {
                accumulated += list[i].pcm.size
                startIndex = i
                if (accumulated >= bytesNeeded) break
            }
            val result = mutableListOf<ByteArray>()
            for (i in startIndex until list.size) {
                val chunk = list[i].pcm
                if (i == startIndex && accumulated > bytesNeeded) {
                    val skip = accumulated - bytesNeeded
                    if (skip < chunk.size) {
                        result.add(chunk.copyOfRange(skip, chunk.size))
                    }
                } else {
                    result.add(chunk)
                }
            }
            return result
        }

        /** Opens a live session in the background; audio goes through the segmenter until it is ready. */
        private fun connectLive() {
            if (liveUnavailable || live != null || liveConnecting?.isActive == true) return
            liveStatus = LiveRecognitionStatus.RECONNECTING
            updateLiveStatus()
            liveConnecting = scope.launch {
                val configured = currentLanguage.tag
                val languageTag = if (configured == "auto") (detectedLanguage ?: "auto") else configured
                val language = LanguageCode(languageTag)
                val result = openLive(language)
                if (result == null) {
                    liveUnavailable = true
                    liveStatus = LiveRecognitionStatus.OFF
                    updateLiveStatus()
                    synchronized(liveLock) {
                        flushUnsentBufferToSegmenter()
                        segmenter.flush()?.let(::announce)
                    }
                    return@launch
                }
                result.fold(
                    success = { session ->
                        if (stopping || paused) {
                            session.cancel()
                            return@fold
                        }
                        val actualGapSamples = synchronized(liveLock) {
                            // What the segmenter was in the middle of is finished the old way.
                            segmenter.flush()?.let(::announce)
                            liveFailures = 0
                            liveStatus = LiveRecognitionStatus.CONNECTED
                            updateLiveStatus()

                            val gapSamples = (samplesWritten - samplesSentToLive).coerceAtLeast(0)
                            var replayed = 0L
                            if (gapSamples > 0) {
                                val gapBytes = (gapSamples * 2).coerceAtMost(rollingBufferBytes.toLong()).toInt()
                                val gapChunks = extractLastBytes(gapBytes)
                                for (gapChunk in gapChunks) {
                                    session.sendAudio(gapChunk)
                                }
                                samplesSentToLive = samplesWritten
                                replayed = (gapBytes / 2).toLong()
                            }
                            segmenter.reset(samplesWritten)
                            live = session
                            replayed
                        }
                        liveStartSample = samplesWritten - actualGapSamples
                        lastFinalSample = liveStartSample
                        utteranceStartSample = null
                        liveEvents = scope.launch { session.events.collect { onLiveEvent(session, it) } }
                        logger.info("Live recognition active for $source")
                    },
                    failure = { error ->
                        liveUnavailable = true
                        liveStatus = LiveRecognitionStatus.OFF
                        updateLiveStatus()
                        synchronized(liveLock) {
                            flushUnsentBufferToSegmenter()
                            segmenter.flush()?.let(::announce)
                        }
                        if (error is ServiceError.ConfigurationError) {
                            val msg = error.message
                            val shouldShowNotice = error.reason == ServiceError.ConfigurationError.Reason.MODEL_NOT_INSTALLED
                            if (shouldShowNotice) {
                                _state.update { it.copy(notice = RecorderNotice.LiveUnavailable(source, msg)) }
                            }
                        } else {
                            liveFailed(error.message)
                        }
                    }
                )
            }
        }

        /** Settles and closes the live session, waiting for its last words. */
        private suspend fun closeLive() {
            val session = synchronized(liveLock) {
                val s = live
                live = null
                s
            } ?: return
            withTimeoutOrNull(LIVE_FINISH_MILLIS) {
                session.finish()
                liveEvents?.join()
            }
            liveEvents?.cancel()
            clearLiveLine()
        }

        fun switchLiveLanguage(language: LanguageCode) {
            if (currentLanguage == language && (live != null || liveConnecting?.isActive == true)) return
            currentLanguage = language
            scope.launch {
                closeLive()
                liveUnavailable = false
                connectLive()
            }
        }

        private var totalWordsConfirmed = 0
        private var lastPartialText: String = ""

        private suspend fun onLiveEvent(session: LiveSpeechSession, event: LiveSpeechEvent) {
            when (event) {
                is LiveSpeechEvent.Partial -> {
                    if (event.text.isBlank()) return
                    val startSample = utteranceStartSample
                        ?: listOfNotNull(voicedStartSample).minOrNull()
                        ?: (samplesWritten - LEAD_SAMPLES).coerceAtLeast(lastFinalSample).also { utteranceStartSample = it }
                    if (utteranceStartSample == null) {
                        utteranceStartSample = startSample
                    }
                    val lockedStart = utteranceStartSample!!
                    if (verifiedUpToSample == null) {
                        verifiedUpToSample = lockedStart
                    }
                    lastPartialText = event.text

                    val checkpointStart = verifiedUpToSample!!
                    val currentSamples = samplesWritten
                    val unverifiedSamples = (currentSamples - checkpointStart).coerceAtLeast(0)
                    val unverifiedMillis = unverifiedSamples * 1000 / PcmStream.SAMPLE_RATE

                    val allWords = event.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    val unverifiedWordCount = (allWords.size - totalWordsConfirmed).coerceAtLeast(0)
                    val timedWords = event.words.map { w ->
                        TranscriptWord(
                            text = w.text,
                            startMillis = toSessionMillis(liveStartSample) + w.startMillis,
                            endMillis = toSessionMillis(liveStartSample) + w.endMillis,
                            verificationState = VerificationState.LIVE,
                            confidence = w.confidence
                        )
                    }

                    val isVerifier = isIndependentVerifier()
                    val shouldVerify = isVerifier && ((unverifiedMillis >= ROLLING_VERIFICATION_MIN_MILLIS && unverifiedWordCount >= ROLLING_VERIFICATION_MIN_WORDS) ||
                        (unverifiedMillis >= ROLLING_VERIFICATION_MAX_MILLIS && unverifiedWordCount > 0))

                    if (shouldVerify && (verificationJob == null || verificationJob?.isActive == false)) {
                        val sliceStartMillis = toSessionMillis(checkpointStart)
                        val currentEndMillis = toSessionMillis(currentSamples)
                        val wordCut = if (timedWords.isNotEmpty()) {
                            VerificationSlicer.chooseWordBoundaryCut(timedWords, sliceStartMillis, currentEndMillis)
                        } else null

                        if (timedWords.isEmpty() || wordCut != null) {
                            val checkpointEnd: Long
                            val wordsAtCheckpoint: Int
                            val sliceWordsText: String

                            if (wordCut != null) {
                                checkpointEnd = (((wordCut.cutMillis - (offsetMillis ?: 0)) * PcmStream.SAMPLE_RATE) / 1000)
                                    .coerceIn(checkpointStart + 1, currentSamples)
                                wordsAtCheckpoint = wordCut.totalWordsIncluded
                                sliceWordsText = wordCut.sliceWords.joinToString(" ") { it.text }
                            } else {
                                val rawWindow = WavFiles.readRange(
                                    repository.audioFile(sessionId, source),
                                    checkpointStart * 1000 / PcmStream.SAMPLE_RATE,
                                    currentSamples * 1000 / PcmStream.SAMPLE_RATE
                                )
                                val minCutSample = (checkpointStart + ROLLING_VERIFICATION_MIN_MILLIS * PcmStream.SAMPLE_RATE / 1000)
                                    .coerceAtMost(currentSamples)
                                checkpointEnd = VerificationSlicer.findLowEnergyCutSample(
                                    pcm = rawWindow,
                                    bufferStartSample = checkpointStart,
                                    searchStartSample = minCutSample,
                                    searchEndSample = currentSamples
                                ).coerceIn(checkpointStart + 1, currentSamples)
                                wordsAtCheckpoint = allWords.size
                                sliceWordsText = allWords.take(wordsAtCheckpoint).drop(totalWordsConfirmed).joinToString(" ")
                            }
                            verifiedUpToSample = checkpointEnd

                            val overlapSamples = (VerificationSlicer.CONTEXT_OVERLAP_MILLIS * PcmStream.SAMPLE_RATE) / 1000
                            val audioExtractStart = if (checkpointStart > lockedStart) {
                                (checkpointStart - overlapSamples).coerceAtLeast(lockedStart)
                            } else checkpointStart

                            verificationJob = scope.launch {
                                val audio = WavFiles.readRange(
                                    repository.audioFile(sessionId, source),
                                    audioExtractStart * 1000 / PcmStream.SAMPLE_RATE,
                                    checkpointEnd * 1000 / PcmStream.SAMPLE_RATE
                                )
                                if (audio.isNotEmpty()) {
                                    val currentSess = _state.value.session
                                    val lang = currentSess?.languageOf(source) ?: "auto"
                                    val sliceSegment = TranscriptSegment(
                                        id = UUID.randomUUID().toString(),
                                        source = source,
                                        startMillis = toSessionMillis(audioExtractStart),
                                        endMillis = toSessionMillis(checkpointEnd),
                                        text = sliceWordsText
                                    )
                                    val prompt = listOfNotNull(
                                        currentSess?.let { promptBefore(it, sliceSegment) },
                                        confirmedText.takeIf { it.isNotBlank() }
                                    ).joinToString(" ").takeIf { it.isNotBlank() }

                                    val outcome = transcriber.transcribe(sliceSegment, audio, LanguageCode(lang), prompt)
                                    when (outcome) {
                                        is SegmentTranscriber.Outcome.Done -> {
                                            val (filteredText, filteredWords) = if (audioExtractStart < checkpointStart && outcome.segment.words.isNotEmpty()) {
                                                VerificationSlicer.filterOutOverlapWords(outcome.segment.words, outcome.segment.text, sliceStartMillis)
                                            } else {
                                                outcome.segment.text.trim() to outcome.segment.words
                                            }
                                            if (filteredText.isNotBlank()) {
                                                val aligned = WordAlignment.align(
                                                    liveText = sliceWordsText,
                                                    accurateText = filteredText,
                                                    accurateWords = filteredWords,
                                                    defaultStartMillis = toSessionMillis(checkpointStart),
                                                    defaultEndMillis = toSessionMillis(checkpointEnd)
                                                )
                                                val verified = filteredText.trim()
                                                confirmedText = if (confirmedText.isBlank()) verified else "$confirmedText $verified"
                                                confirmedWords = confirmedWords + aligned.words
                                                totalWordsConfirmed = wordsAtCheckpoint
                                                matchedWordsTotal += aligned.matchedCount
                                                liveWordsCheckedTotal += aligned.liveWordCount
                                                accurateWordsCheckedTotal += aligned.accurateWordCount
                                                val denom = maxOf(liveWordsCheckedTotal, accurateWordsCheckedTotal)
                                                liveAgreement = if (denom > 0) matchedWordsTotal.toDouble() / denom.toDouble() else 1.0
                                                liveVerificationState = if (liveVerificationState == VerificationState.CORRECTED || aligned.state == VerificationState.CORRECTED) {
                                                    VerificationState.CORRECTED
                                                } else {
                                                    aligned.state
                                                }
                                            }
                                        }
                                    is SegmentTranscriber.Outcome.NoService -> {
                                        liveVerificationState = VerificationState.UNVERIFIED
                                    }
                                    is SegmentTranscriber.Outcome.Failed -> {
                                        liveVerificationState = VerificationState.UNVERIFIED
                                    }
                                }

                                val latestWords = lastPartialText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                                val liveTail = if (latestWords.size > totalWordsConfirmed) latestWords.drop(totalWordsConfirmed).joinToString(" ") else ""
                                val fullText = if (confirmedText.isBlank()) lastPartialText else if (liveTail.isBlank()) confirmedText else "$confirmedText $liveTail"
                                    _state.update { it.copy(live = it.live + (source to LiveLine(toSessionMillis(lockedStart), fullText, confirmedText, confirmedWords, liveVerificationState, liveAgreement))) }
                                }
                            }
                        }
                    }

                    val liveTail = if (allWords.size > totalWordsConfirmed) allWords.drop(totalWordsConfirmed).joinToString(" ") else ""
                    val fullText = if (confirmedText.isBlank()) event.text else if (liveTail.isBlank()) confirmedText else "$confirmedText $liveTail"
                    val unconfirmedLiveWords = if (timedWords.size > totalWordsConfirmed) timedWords.drop(totalWordsConfirmed) else emptyList()
                    _state.update { it.copy(live = it.live + (source to LiveLine(toSessionMillis(lockedStart), fullText, confirmedText, confirmedWords, liveVerificationState, liveAgreement, unconfirmedLiveWords))) }
                }
                is LiveSpeechEvent.Final -> settle(event)
                is LiveSpeechEvent.Failed -> {
                    val s = synchronized(liveLock) {
                        if (live === session) {
                            live = null
                            session
                        } else null
                    }
                    if (s != null) {
                        clearLiveLine()
                        liveFailed(event.error.message)
                    }
                }
            }
        }

        /** Turns a live final into a segment, and hands its audio to the accurate recognizer. */
        private fun settle(event: LiveSpeechEvent.Final) {
            val endSample = samplesWritten
            val startSample = utteranceStartSample ?: listOfNotNull(voicedStartSample).minOrNull()
                ?.coerceIn(lastFinalSample, endSample) ?: lastFinalSample
            utteranceStartSample = null
            voicedStartSample = null
            lastFinalSample = endSample
            synchronized(liveLock) { segmenter.resetUtterance() }
            if (event.text.isBlank()) {
                clearLiveLine()
                return
            }

            // Wait briefly for in-flight rolling verification job if active
            val inFlight = verificationJob
            if (inFlight?.isActive == true) {
                runBlocking {
                    withTimeoutOrNull(500L) { inFlight.join() }
                }
            }

            val words = event.words.map { word ->
                TranscriptWord(
                    text = word.text,
                    startMillis = toSessionMillis(liveStartSample) + word.startMillis,
                    endMillis = toSessionMillis(liveStartSample) + word.endMillis,
                    confidence = word.confidence
                )
            }
            val startMillis = toSessionMillis(startSample)
            val endMillis = toSessionMillis(endSample)

            val sliceStartSample = verifiedUpToSample?.coerceAtLeast(startSample) ?: startSample
            val hasConfirmed = confirmedText.isNotBlank() && sliceStartSample > startSample
            val currentConfirmedText = confirmedText
            val currentConfirmedWords = confirmedWords
            val currentTotalWordsConfirmed = totalWordsConfirmed
            val currentVerificationState = liveVerificationState
            val currentAgreement = liveAgreement
            val currentMatchedWords = matchedWordsTotal
            val currentLiveWordsChecked = liveWordsCheckedTotal
            val currentAccurateWordsChecked = accurateWordsCheckedTotal

            // Reset rolling verification state
            verifiedUpToSample = null
            confirmedText = ""
            confirmedWords = emptyList()
            liveVerificationState = VerificationState.LIVE
            liveAgreement = null
            matchedWordsTotal = 0
            liveWordsCheckedTotal = 0
            accurateWordsCheckedTotal = 0
            totalWordsConfirmed = 0
            verificationJob = null
            lastPartialText = ""

            val allWords = event.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val tailLiveText = if (hasConfirmed && allWords.size > currentTotalWordsConfirmed) {
                allWords.drop(currentTotalWordsConfirmed).joinToString(" ")
            } else if (!hasConfirmed) {
                event.text
            } else ""

            val fullDraftText = when {
                !hasConfirmed -> event.text
                tailLiveText.isBlank() -> currentConfirmedText
                else -> "${currentConfirmedText.trimEnd()} ${tailLiveText.trimStart()}"
            }

            val fullWords = if (hasConfirmed) {
                currentConfirmedWords + words.filter { it.startMillis >= toSessionMillis(sliceStartSample) }
            } else words

            val currentSession = _state.value.session
            val settleTime = maxOf(endMillis, currentSession?.segments?.maxOfOrNull { it.settledAtMillis } ?: 0L)
            val draft = TranscriptSegment(
                id = UUID.randomUUID().toString(),
                source = source,
                startMillis = startMillis,
                endMillis = endMillis,
                text = fullDraftText,
                words = fullWords,
                confirmedText = currentConfirmedText,
                draft = true,
                verificationState = currentVerificationState,
                agreement = currentAgreement,
                settledAtMillis = settleTime
            )

            val candidate = currentSession?.findCoalesceCandidate(source, startMillis, endMillis, currentLanguage.tag)

            val segmentToApply: TranscriptSegment
            val combinedAudioStartSample: Long
            if (candidate != null) {
                segmentToApply = coalesceSegments(candidate, draft)
                combinedAudioStartSample = ((candidate.startMillis - (offsetMillis ?: 0)) * PcmStream.SAMPLE_RATE / 1000)
                    .coerceIn(0, endSample)
            } else {
                segmentToApply = draft
                combinedAudioStartSample = if (hasConfirmed) sliceStartSample else startSample
            }

            var sessionToSave: ConversationSession? = null
            _state.update { current ->
                val s = current.session ?: return@update current.copy(live = current.live - source)
                val updated = applySegment(s, segmentToApply)
                sessionToSave = updated
                current.copy(
                    live = current.live - source,
                    session = updated
                )
            }

            sessionToSave?.let { session ->
                scope.launch(Dispatchers.IO) {
                    sessionLock.withLock {
                        runCatching { repository.save(session) }.onFailure { e ->
                            logger.error("Could not save conversation transcript", e)
                            _state.update { it.copy(notice = RecorderNotice.SaveFailed(e.message ?: e.toString())) }
                        }
                    }
                }
            }

            val tailDurationMillis = (endSample - combinedAudioStartSample) * 1000 / PcmStream.SAMPLE_RATE
            if (!isIndependentVerifier()) {
                // In Local only mode, do not self-verify with the same offline engine; settle directly as UNVERIFIED
                scope.launch {
                    updateSession {
                        applySegment(it, segmentToApply.copy(draft = false, verificationState = VerificationState.UNVERIFIED, agreement = null))
                    }
                }
            } else if (hasConfirmed && candidate == null && (tailDurationMillis < 200 || tailLiveText.isBlank())) {
                val settledState = if (currentVerificationState == VerificationState.LIVE) VerificationState.VERIFIED else currentVerificationState
                scope.launch { updateSession { applySegment(it, segmentToApply.copy(draft = false, verificationState = settledState)) } }
            } else {
                val audio = WavFiles.readRange(
                    repository.audioFile(sessionId, source),
                    combinedAudioStartSample * 1000 / PcmStream.SAMPLE_RATE,
                    endSample * 1000 / PcmStream.SAMPLE_RATE
                )
                if (audio.isEmpty()) {
                    val emptyAudioState = if (hasConfirmed && currentVerificationState != VerificationState.LIVE) currentVerificationState else VerificationState.UNVERIFIED
                    scope.launch { updateSession { applySegment(it, segmentToApply.copy(draft = false, verificationState = emptyAudioState)) } }
                } else {
                    if (hasConfirmed && candidate == null) {
                        scope.launch {
                            val tailPlaceholder = TranscriptSegment(
                                id = segmentToApply.id,
                                source = source,
                                startMillis = toSessionMillis(combinedAudioStartSample),
                                endMillis = toSessionMillis(endSample),
                                text = tailLiveText,
                                draft = true
                            )
                            val sess = _state.value.session
                            val lang = sess?.languageOf(source) ?: "auto"
                            val outcome = transcriber.transcribe(
                                tailPlaceholder, audio, LanguageCode(lang), promptBefore(sess ?: currentSession!!, segmentToApply)
                            )
                            val tailVerifiedText: String
                            val tailState: VerificationState
                            val tailVerifiedWords: List<TranscriptWord>
                            val combinedAgreement: Double?
                            when (outcome) {
                                is SegmentTranscriber.Outcome.Done -> {
                                    if (outcome.segment.text.isNotBlank()) {
                                        val alignedTail = WordAlignment.align(
                                            liveText = tailLiveText,
                                            accurateText = outcome.segment.text,
                                            accurateWords = outcome.segment.words,
                                            defaultStartMillis = toSessionMillis(combinedAudioStartSample),
                                            defaultEndMillis = toSessionMillis(endSample)
                                        )
                                        tailVerifiedText = outcome.segment.text.trim()
                                        tailState = alignedTail.state
                                        tailVerifiedWords = alignedTail.words
                                        val totMatched = currentMatchedWords + alignedTail.matchedCount
                                        val totDenom = maxOf(currentLiveWordsChecked + alignedTail.liveWordCount, currentAccurateWordsChecked + alignedTail.accurateWordCount)
                                        combinedAgreement = if (totDenom > 0) totMatched.toDouble() / totDenom.toDouble() else currentAgreement
                                    } else {
                                        tailVerifiedText = ""
                                        tailState = currentVerificationState
                                        tailVerifiedWords = emptyList()
                                        combinedAgreement = currentAgreement
                                    }
                                }
                                is SegmentTranscriber.Outcome.NoService,
                                is SegmentTranscriber.Outcome.Failed -> {
                                    tailVerifiedText = tailLiveText
                                    tailState = VerificationState.UNVERIFIED
                                    tailVerifiedWords = words.filter { it.startMillis >= toSessionMillis(combinedAudioStartSample) }
                                        .map { it.copy(verificationState = VerificationState.UNVERIFIED) }
                                    combinedAgreement = currentAgreement
                                }
                            }

                            val stitchedText = if (currentConfirmedText.isBlank()) tailVerifiedText
                                else if (tailVerifiedText.isBlank()) currentConfirmedText
                                else "${currentConfirmedText.trimEnd()} ${tailVerifiedText.trimStart()}"
                            val stitchedWords = currentConfirmedWords + tailVerifiedWords
                            val finalState = when {
                                tailState == VerificationState.UNVERIFIED || currentVerificationState == VerificationState.UNVERIFIED -> VerificationState.UNVERIFIED
                                tailState == VerificationState.CORRECTED || currentVerificationState == VerificationState.CORRECTED -> VerificationState.CORRECTED
                                else -> VerificationState.VERIFIED
                            }

                            updateSession { s ->
                                applySegment(s, segmentToApply.copy(
                                    text = stitchedText,
                                    words = stitchedWords,
                                    draft = false,
                                    verificationState = finalState,
                                    agreement = combinedAgreement
                                ))
                            }
                        }
                    } else {
                        utterances.trySend(segmentToApply to audio)
                    }
                }
            }
        }

        private fun liveFailed(message: String) {
            logger.warn("Live recognition for $source failed: $message")
            _state.update { it.copy(notice = RecorderNotice.LiveFailed(source, message)) }
            if (stopping) return
            liveFailures++
            if (liveFailures > MAX_LIVE_RECONNECTS) {
                liveUnavailable = true
                liveStatus = LiveRecognitionStatus.OFF
                updateLiveStatus()
                synchronized(liveLock) {
                    flushUnsentBufferToSegmenter()
                    segmenter.flush()?.let(::announce)
                }
                return
            }
            liveStatus = LiveRecognitionStatus.RECONNECTING
            updateLiveStatus()
            // The same back-off as the Vosk extension: quick at first, never more than 5 s apart.
            val delayMillis = minOf((200 * Math.pow(1.5, (liveFailures - 1).toDouble())).toLong(), 5_000L)
            scope.launch {
                delay(delayMillis)
                if (!stopping && !paused) connectLive()
            }
        }

        private fun clearLiveLine() = _state.update { it.copy(live = it.live - source) }

        private fun toSessionMillis(streamSample: Long): Long =
            (offsetMillis ?: 0) + streamSample * 1000 / PcmStream.SAMPLE_RATE

        fun stopCapture() {
            stopping = true
            runCatching { pcm?.close() }
        }

        private fun announce(utterance: SpeechSegmenter.Utterance) {
            val offset = offsetMillis ?: 0
            val start = offset + utterance.startSample * 1000 / PcmStream.SAMPLE_RATE
            val placeholder = TranscriptSegment(
                id = UUID.randomUUID().toString(),
                source = source,
                startMillis = start,
                endMillis = start + utterance.sampleCount * 1000L / PcmStream.SAMPLE_RATE,
                pending = true
            )
            // Inserted asynchronously, so a fast transcription may land first; never overwrite it.
            scope.launch {
                updateSession { s -> if (s.segments.any { it.id == placeholder.id }) s else s.withSegment(placeholder) }
            }
            utterances.trySend(placeholder to utterance.pcm)
        }
    }

    private fun newSessionId(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(clock())) +
            "-" + UUID.randomUUID().toString().take(6)

    internal companion object {
        const val TICK_MILLIS = 200L
        const val SHUTDOWN_MILLIS = 5_000L
        const val MAX_CONCURRENT_TRANSCRIPTIONS = 3
        const val MAX_LIVE_RECONNECTS = 10
        const val LIVE_FINISH_MILLIS = 4_000L
        /** How far before the first partial a live segment's audio starts: words begin before they are recognised. */
        const val LEAD_SAMPLES = PcmStream.SAMPLE_RATE * 6 / 10
        const val ROLLING_BUFFER_SECONDS = 10
        const val ROLLING_BUFFER_MAX_BYTES = PcmStream.SAMPLE_RATE * 2 * ROLLING_BUFFER_SECONDS
        const val ROLLING_VERIFICATION_MIN_MILLIS = 4_500L
        const val ROLLING_VERIFICATION_MIN_WORDS = 8
        const val ROLLING_VERIFICATION_MAX_MILLIS = 6_000L

        fun computePcmRms(pcm: ByteArray): Double {
            if (pcm.isEmpty()) return 0.0
            var sum = 0.0
            val count = pcm.size / 2
            for (i in 0 until pcm.size step 2) {
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                sum += sample.toShort().toDouble() * sample.toShort().toDouble()
            }
            return kotlin.math.sqrt(sum / count)
        }

        fun isEchoPair(systemSegment: TranscriptSegment, micSegment: TranscriptSegment): Boolean {
            val sys = if (systemSegment.source == AudioSource.SYSTEM) systemSegment else micSegment
            val mic = if (systemSegment.source == AudioSource.MICROPHONE) systemSegment else micSegment
            if (sys.source != AudioSource.SYSTEM || mic.source != AudioSource.MICROPHONE) return false
            if (sys.text.isBlank() || mic.text.isBlank()) return false
            if (kotlin.math.abs(sys.startMillis - mic.startMillis) > 12_000) return false

            val sysText = sys.text.trim().lowercase()
            val micText = mic.text.trim().lowercase()
            if (sysText.length < 5 || micText.length < 5) return false

            val sysWords = sysText.split(Regex("""[\s\p{Punct}]+""")).filter { it.isNotBlank() }
            val micWords = micText.split(Regex("""[\s\p{Punct}]+""")).filter { it.isNotBlank() }
            if (sysWords.size < 2 && micWords.size < 2 && sysText.length < 10) return false

            if (sysText == micText ||
                (sysWords.size >= 3 && micText.contains(sysText)) ||
                (micWords.size >= 3 && sysText.contains(micText))
            ) {
                return true
            }

            val set1 = sysWords.toSet()
            val set2 = micWords.toSet()
            val intersection = set1.intersect(set2).size
            val union = set1.union(set2).size
            if (union > 0) {
                val jaccard = intersection.toDouble() / union
                if (jaccard >= 0.6 && intersection >= 2) {
                    return true
                }
            }
            return false
        }
    }
}

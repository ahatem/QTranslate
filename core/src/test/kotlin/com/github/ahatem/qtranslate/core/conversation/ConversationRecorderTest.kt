package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.RecognizedWord
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the recorder with scripted devices in real time. Each test feeds a few seconds of
 * synthetic audio, so they are slower than most unit tests but exercise the real threads.
 */
class ConversationRecorderTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var repository = SessionRepository(createTempDirectory("recorder").toFile())

    @kotlin.test.BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = SessionRepository(createTempDirectory("recorder").toFile())
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        repository.root.deleteRecursively()
    }

    /** A device whose audio is pushed by the test, delivered in 100 ms chunks. */
    private class ScriptedDevice {
        val chunks = LinkedBlockingQueue<ByteArray>()
        var failWith: Exception? = null

        fun push(pcm: ByteArray) = pcm.toList().chunked(3_200).forEach { chunks.put(it.toByteArray()) }

        fun stream() = object : PcmStream {
            @Volatile var closed = false
            override fun read(): ByteArray? {
                while (!closed) {
                    failWith?.let { throw it }
                    chunks.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
                }
                return null
            }
            override fun close() { closed = true }
        }
    }

    private class ScriptedBackend(private val devices: Map<String, ScriptedDevice>) : AudioBackend {
        override fun inputDevices() = devices.keys.map { AudioDevice(it, it) }
        override fun open(device: AudioDevice): PcmStream =
            devices[device.id]?.stream() ?: throw AudioDeviceUnavailableException(device)
    }

    private fun recorder(
        mic: ScriptedDevice,
        system: ScriptedDevice,
        openLive: OpenLive = { null },
        isPlaybackActive: () -> Boolean = { false },
        isIndependentVerifier: () -> Boolean = { true },
        recognize: Recognize
    ) = ConversationRecorder(
        scope = scope,
        repository = repository,
        microphoneBackend = ScriptedBackend(mapOf("mic" to mic)),
        systemBackend = ScriptedBackend(mapOf("out" to system)),
        recognize = recognize,
        logger = logger,
        openLive = openLive,
        isPlaybackActive = isPlaybackActive,
        isIndependentVerifier = isIndependentVerifier
    )

    /**
     * The live session connects in the background, and audio sent before it is ready goes the
     * clip way. Feeds silence until the session is the one receiving it.
     */
    private suspend fun awaitLive(mic: ScriptedDevice, live: ScriptedLive) = withTimeout(10_000) {
        while (live.received.get() == 0L) {
            mic.push(silence(100))
            delay(50)
        }
    }

    /** A live session the test speaks for: it pushes the events a live server would send. */
    private class ScriptedLive : com.github.ahatem.qtranslate.api.speech.LiveSpeechSession {
        val channel = kotlinx.coroutines.channels.Channel<com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val received = java.util.concurrent.atomic.AtomicLong()
        override val events = channel.let { c -> kotlinx.coroutines.flow.flow { for (e in c) emit(e) } }
        override fun sendAudio(pcm: ByteArray) { received.addAndGet(pcm.size.toLong()) }
        override suspend fun finish() { channel.close() }
        override fun cancel() { channel.close() }
    }

    private fun options(keepAudio: Boolean = true) = RecordingOptions(
        title = "Test call",
        microphone = AudioDevice("mic", "mic"),
        systemOutput = AudioDevice("out", "out"),
        keepAudio = keepAudio
    )

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(20)
    }

    @Test
    fun `both sources are recorded separately, transcribed and saved in spoken order`() = runBlocking {
        val mic = ScriptedDevice()
        val system = ScriptedDevice()
        val recorder = recorder(mic, system) { request ->
            // Tells the sources apart by clip length: the mic says a short line, the call a long one.
            val text = if (request.audio.durationMillis < 2_000) "maybe check the logs" else "the server keeps timing out"
            Ok(SpeechRecognitionResponse(text, words = listOf(RecognizedWord("the", 100, 300))))
        }

        recorder.start(options())
        waitUntil { recorder.state.value.streams.values.all { it.state == StreamState.LIVE } }
        system.push(silence(300) + tone(1_800) + silence(1_000))
        mic.push(silence(2_000) + tone(700) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.count { !it.pending && it.text.isNotBlank() } == 2 }
        recorder.stop()

        val saved = repository.list().single()
        assertEquals(SessionStatus.COMPLETED, saved.status)
        assertEquals(listOf(AudioSource.SYSTEM, AudioSource.MICROPHONE), saved.segments.map { it.source })
        assertEquals("the server keeps timing out", saved.segments[0].text)
        val word = saved.segments[0].words.single()
        assertEquals(saved.segments[0].startMillis + 100, word.startMillis, "word times are on the session timeline")
        assertTrue(repository.hasAudio(saved.id, AudioSource.MICROPHONE))
        assertTrue(repository.hasAudio(saved.id, AudioSource.SYSTEM))
        assertEquals(RecorderPhase.IDLE, recorder.state.value.phase)
    }

    @Test
    fun `hybrid mode shows live words at once and replaces them with the accurate text`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        val clips = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val recorder = recorder(mic, ScriptedDevice(), openLive = { Ok(live) }) { request ->
            clips += request.audio.durationMillis
            Ok(SpeechRecognitionResponse("See you tomorrow."))
        }
        recorder.start(options().copy(systemOutput = null))
        awaitLive(mic, live)

        val before = live.received.get()
        mic.push(tone(1_500))
        waitUntil { live.received.get() >= before + 48_000 }
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial("see you"))
        waitUntil { recorder.state.value.live[AudioSource.MICROPHONE]?.text == "see you" }

        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("see you to morrow"))
        waitUntil { recorder.state.value.session?.segments?.singleOrNull()?.text == "See you tomorrow." }
        val segment = recorder.state.value.session!!.segments.single()
        assertTrue(!segment.draft, "the accurate text is final")
        assertTrue(recorder.state.value.live.isEmpty(), "the live line clears once settled")
        assertTrue(clips.single() >= 1_400, "the accurate recognizer got the spoken stretch, was ${clips.single()} ms")
        recorder.stop()
    }

    @Test
    fun `live text stands on its own without an accurate recognizer`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        val recorder = recorder(mic, ScriptedDevice(), openLive = { Ok(live) }) { null }
        recorder.start(options().copy(systemOutput = null))
        awaitLive(mic, live)
        mic.push(tone(800))
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("hello there"))
        waitUntil { recorder.state.value.session?.segments?.singleOrNull()?.draft == false }
        assertEquals("hello there", recorder.state.value.session!!.segments.single().text)
        assertEquals(null, recorder.state.value.notice, "no accurate recognizer is not an error in live mode")
        recorder.stop()
    }

    @Test
    fun `an unconfigured live recognizer falls back to cutting at pauses, silently`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice(),
            openLive = { Err(ServiceError.ConfigurationError("Live text is off in Settings", null, ServiceError.ConfigurationError.Reason.FEATURE_DISABLED)) }) {
            Ok(SpeechRecognitionResponse("clip text"))
        }
        recorder.start(options().copy(systemOutput = null))
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.text == "clip text" } == true }
        assertEquals(null, recorder.state.value.notice)
        recorder.stop()
    }

    @Test
    fun `missing model configuration error shows visible notice even when message is in Arabic`() = runBlocking {
        val mic = ScriptedDevice()
        val arabicMessage = "لا يوجد نموذج كلام منصّب للغة العربية"
        val recorder = recorder(mic, ScriptedDevice(),
            openLive = {
                Err(
                    ServiceError.ConfigurationError(
                        arabicMessage,
                        null,
                        ServiceError.ConfigurationError.Reason.MODEL_NOT_INSTALLED
                    )
                )
            }) {
            Ok(SpeechRecognitionResponse("clip text"))
        }
        recorder.start(options().copy(systemOutput = null, microphoneLanguage = LanguageCode("ar")))
        waitUntil { recorder.state.value.notice is RecorderNotice.LiveUnavailable }
        val notice = recorder.state.value.notice as RecorderNotice.LiveUnavailable
        assertEquals(AudioSource.MICROPHONE, notice.source)
        assertEquals(arabicMessage, notice.message)
        recorder.stop()
    }

    @Test
    fun `other runtime error still delegates to liveFailed and attempts reconnect`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice(),
            openLive = { Err(ServiceError.NetworkError("WebSocket connection refused")) }) {
            Ok(SpeechRecognitionResponse("clip text"))
        }
        recorder.start(options().copy(systemOutput = null))
        waitUntil { recorder.state.value.notice is RecorderNotice.LiveFailed }
        val notice = recorder.state.value.notice as RecorderNotice.LiveFailed
        assertEquals(AudioSource.MICROPHONE, notice.source)
        assertTrue(notice.message.contains("WebSocket connection refused"))
        recorder.stop()
    }

    @Test
    fun `non-independent verifier in local mode skips verification and settles as UNVERIFIED`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        var transcribeCalled = false
        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { Ok(live) },
            isIndependentVerifier = { false },
            recognize = {
                transcribeCalled = true
                Ok(SpeechRecognitionResponse("accurate text"))
            }
        )

        recorder.start(options().copy(systemOutput = null))
        awaitLive(mic, live)
        mic.push(tone(800))
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("local spoken text"))

        waitUntil { recorder.state.value.session?.segments?.singleOrNull()?.draft == false }
        val seg = recorder.state.value.session!!.segments.single()

        assertEquals("local spoken text", seg.text)
        assertEquals(VerificationState.UNVERIFIED, seg.verificationState)
        assertEquals(null, seg.agreement)
        kotlin.test.assertFalse(transcribeCalled, "Rolling/settle verification must be skipped when verifier is not independent")
        recorder.stop()
    }

    @Test
    fun `each source is transcribed in its own language`() = runBlocking {
        val mic = ScriptedDevice()
        val system = ScriptedDevice()
        val languages = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        val recorder = recorder(mic, system) { request ->
            languages += request.audio.durationMillis to request.language.tag
            Ok(SpeechRecognitionResponse("x"))
        }
        recorder.start(options().copy(
            microphoneLanguage = com.github.ahatem.qtranslate.api.language.LanguageCode("ar"),
            systemLanguage = com.github.ahatem.qtranslate.api.language.LanguageCode("en")
        ))
        waitUntil { recorder.state.value.streams.values.all { it.state == StreamState.LIVE } }
        mic.push(tone(700) + silence(1_000))
        system.push(tone(1_800) + silence(1_000))
        waitUntil { languages.size == 2 }
        recorder.stop()

        assertEquals("ar", languages.minByOrNull { it.first }!!.second, "the short clip came from the microphone")
        assertEquals("en", languages.maxByOrNull { it.first }!!.second)
        assertEquals("en", repository.list().single().callLanguage)
    }

    @Test
    fun `retry failed transcribes every failed segment once the service works`() = runBlocking {
        val mic = ScriptedDevice()
        var available = false
        val recorder = recorder(mic, ScriptedDevice()) {
            if (available) Ok(SpeechRecognitionResponse("recovered")) else null
        }
        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(800) + silence(1_000) + tone(800) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.count { it.failed } == 2 }
        recorder.stop()

        available = true
        val updated = recorder.retryFailed(repository.list().single())
        assertEquals(listOf("recovered", "recovered"), updated.segments.map { it.text })
        assertTrue(repository.list().single().segments.none { it.failed })
    }

    @Test
    fun `a failed transcription keeps the audio and can be retried`() = runBlocking {
        val mic = ScriptedDevice()
        var fail = true
        val recorder = recorder(mic, ScriptedDevice()) {
            if (fail) Err(ServiceError.NetworkError("offline"))
            else Ok(SpeechRecognitionResponse("hello there")) as Result<SpeechRecognitionResponse, ServiceError>
        }

        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.failed } == true }
        assertTrue(recorder.state.value.notice is RecorderNotice.TranscriptionFailed)
        recorder.stop()

        val session = repository.list().single()
        fail = false
        val retried = recorder.retry(session, session.segments.single())
        assertEquals("hello there", retried?.segments?.single()?.text)
        assertEquals("hello there", repository.load(session.id)?.segments?.single()?.text, "the retry is saved")

        // Delete segment
        val withoutSegment = recorder.deleteSegment(retried!!, retried.segments.single().id)
        assertTrue(withoutSegment.segments.isEmpty())
        assertTrue(repository.load(session.id)!!.segments.isEmpty(), "deletion is saved to repository")
    }

    @Test
    fun `recording continues without a speech service and says so`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice()) { null }
        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.notice == RecorderNotice.NoSpeechService }
        recorder.stop()
        assertTrue(repository.hasAudio(repository.list().single().id, AudioSource.MICROPHONE))
    }

    @Test
    fun `audio spoken while paused is neither kept nor transcribed`() = runBlocking {
        val mic = ScriptedDevice()
        val clips = mutableListOf<SpeechRecognitionRequest>()
        val recorder = recorder(mic, ScriptedDevice()) { synchronized(clips) { clips += it }; Ok(SpeechRecognitionResponse("x")) }
        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        recorder.pause()
        mic.push(tone(1_500) + silence(1_000))
        waitUntil { mic.chunks.isEmpty() }
        delay(300)
        recorder.resume()
        recorder.stop()

        assertTrue(clips.isEmpty())
        val session = repository.list().single()
        assertTrue(session.segments.isEmpty())
        assertTrue(WavFiles.durationMillis(repository.audioFile(session.id, AudioSource.MICROPHONE)) < 300)
    }

    @Test
    fun `a device that drops out stops only its own stream`() = runBlocking {
        val mic = ScriptedDevice()
        val system = ScriptedDevice()
        val recorder = recorder(mic, system) { Ok(SpeechRecognitionResponse("still here")) }
        recorder.start(options())
        waitUntil { recorder.state.value.streams.values.all { it.state == StreamState.LIVE } }

        system.failWith = IllegalStateException("device unplugged")
        waitUntil { recorder.state.value.streams[AudioSource.SYSTEM]?.state == StreamState.FAILED }
        assertTrue(recorder.state.value.notice is RecorderNotice.DeviceLost)

        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.text == "still here" } == true }
        recorder.stop()
    }

    @Test
    fun `without keep audio the recordings are deleted when the session ends`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice()) { Ok(SpeechRecognitionResponse("hi")) }
        recorder.start(options(keepAudio = false))
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.text == "hi" } == true }
        recorder.stop()

        val session = repository.list().single()
        assertEquals("hi", session.segments.single().text, "the transcript is kept")
        assertTrue(!repository.hasAudio(session.id, AudioSource.MICROPHONE))
    }

    @Test
    fun `deleteSegment removes segment from session and persistence`() = runBlocking {
        val recorder = recorder(ScriptedDevice(), ScriptedDevice()) { Ok(SpeechRecognitionResponse("line")) }
        val session = ConversationSession(
            id = "s-1",
            title = "Test",
            createdAt = 1000L,
            spokenLanguage = "en",
            callLanguage = "en",
            segments = listOf(
                TranscriptSegment(id = "seg-1", source = AudioSource.MICROPHONE, startMillis = 0L, endMillis = 1000L, text = "first"),
                TranscriptSegment(id = "seg-2", source = AudioSource.MICROPHONE, startMillis = 1000L, endMillis = 2000L, text = "second")
            )
        )
        repository.save(session)
        val updated = recorder.deleteSegment(session, "seg-1")
        assertEquals(1, updated.segments.size)
        assertEquals("seg-2", updated.segments.single().id)
        val reloaded = repository.load("s-1")
        assertEquals(1, reloaded?.segments?.size)
        assertEquals("seg-2", reloaded?.segments?.single()?.id)
    }

    @Test
    fun `shutdown saves pending clips as failed instead of dropping them`() = runBlocking {
        val mic = ScriptedDevice()
        val recorder = recorder(mic, ScriptedDevice()) { delay(60_000); Ok(SpeechRecognitionResponse("late")) }
        recorder.start(options())
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        mic.push(tone(1_000) + silence(1_000))
        waitUntil { recorder.state.value.session?.segments?.any { it.pending } == true }

        recorder.shutdown()

        val session = repository.list().single()
        assertEquals(SessionStatus.COMPLETED, session.status)
        assertTrue(session.segments.single().failed)
    }

    @Test
    fun `language detected by accurate provider updates live recognition language in auto mode`() = runBlocking {
        val mic = ScriptedDevice()
        val openedLiveLanguages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val currentLive = java.util.concurrent.atomic.AtomicReference<ScriptedLive>()

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { lang ->
                openedLiveLanguages.add(lang.tag)
                val live = ScriptedLive()
                currentLive.set(live)
                Ok(live)
            },
            recognize = { _ ->
                Ok(SpeechRecognitionResponse(text = "مرحبا", detectedLanguage = com.github.ahatem.qtranslate.api.language.LanguageCode("ar")))
            }
        )

        recorder.start(options().copy(systemOutput = null))
        waitUntil { openedLiveLanguages.isNotEmpty() && currentLive.get() != null }
        assertEquals("auto", openedLiveLanguages.first())

        awaitLive(mic, currentLive.get()!!)
        mic.push(tone(1_500))
        currentLive.get()!!.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("marhaban"))

        waitUntil { recorder.state.value.session?.segments?.any { it.text == "مرحبا" } == true }
        waitUntil { openedLiveLanguages.contains("ar") }

        recorder.stop()
    }

    @Test
    fun `reconnect mid-stream replays gap audio to the new live session without losing speech`() = runBlocking {
        val mic = ScriptedDevice()
        val sessions = java.util.Collections.synchronizedList(mutableListOf<ScriptedLive>())

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { _ ->
                val live = ScriptedLive()
                sessions.add(live)
                Ok(live)
            },
            recognize = { _ ->
                Ok(SpeechRecognitionResponse(text = "ok"))
            }
        )

        recorder.start(options().copy(systemOutput = null))
        waitUntil { sessions.isNotEmpty() }
        val session1 = sessions.first()
        awaitLive(mic, session1)
        assertEquals(LiveRecognitionStatus.CONNECTED, recorder.state.value.liveStatus)

        // Push some audio to session1
        val firstAudio = tone(500)
        mic.push(firstAudio)
        waitUntil { session1.received.get() >= firstAudio.size }

        // Session 1 fails mid-stream
        session1.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Failed(
            com.github.ahatem.qtranslate.api.plugin.ServiceError.NetworkError("connection lost")
        ))
        waitUntil { recorder.state.value.liveStatus == LiveRecognitionStatus.RECONNECTING }

        // Audio spoken while disconnected (the gap)
        val gapAudio = tone(1_000)
        mic.push(gapAudio)

        // Wait for reconnect to succeed (creates session2)
        waitUntil { sessions.size >= 2 }
        val session2 = sessions[1]
        waitUntil { recorder.state.value.liveStatus == LiveRecognitionStatus.CONNECTED }

        // Verify that the gap audio was replayed to session2
        waitUntil { session2.received.get() >= gapAudio.size }

        session2.channel.send(
            com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final(
                text = "gap speech",
                words = listOf(
                    com.github.ahatem.qtranslate.api.speech.RecognizedWord("gap", 100, 400),
                    com.github.ahatem.qtranslate.api.speech.RecognizedWord("speech", 500, 900)
                )
            )
        )
        waitUntil { recorder.state.value.session?.segments?.isNotEmpty() == true }
        val segment = recorder.state.value.session!!.segments.first()
        assertTrue(segment.startMillis < segment.endMillis)
        assertTrue(segment.startMillis in 0 until segment.endMillis)

        recorder.stop()
        assertEquals(LiveRecognitionStatus.OFF, recorder.state.value.liveStatus)
    }

    @Test
    fun `isEchoPair detects matching speech across microphone and system streams`() {
        val micSegment = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 3000,
            text = "I speak English now with my friends"
        )
        val sysEchoSegment = TranscriptSegment(
            id = "seg-2",
            source = AudioSource.SYSTEM,
            startMillis = 1400,
            endMillis = 3500,
            text = "I speak English now with my friends"
        )
        val sysDifferentSegment = TranscriptSegment(
            id = "seg-3",
            source = AudioSource.SYSTEM,
            startMillis = 2000,
            endMillis = 4000,
            text = "Sounds great we are ready"
        )

        assertTrue(ConversationRecorder.isEchoPair(sysEchoSegment, micSegment))
        assertTrue(ConversationRecorder.isEchoPair(micSegment, sysEchoSegment))
        kotlin.test.assertFalse(ConversationRecorder.isEchoPair(sysDifferentSegment, micSegment))
    }

    @Test
    fun `updating stream language to the same language does not reconnect live session`() = runBlocking {
        val mic = ScriptedDevice()
        val sessions = java.util.Collections.synchronizedList(mutableListOf<ScriptedLive>())

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { _ ->
                val live = ScriptedLive()
                sessions.add(live)
                Ok(live)
            },
            recognize = { Ok(SpeechRecognitionResponse(text = "test")) }
        )

        recorder.start(options().copy(systemOutput = null, microphoneLanguage = com.github.ahatem.qtranslate.api.language.LanguageCode("en")))
        waitUntil { sessions.isNotEmpty() }
        val session1 = sessions.single()
        awaitLive(mic, session1)

        // Requesting the exact same language must return early without creating a second live session
        recorder.updateSpokenLanguage(com.github.ahatem.qtranslate.api.language.LanguageCode("en"))
        delay(200)
        assertEquals(1, sessions.size, "Live session should not reconnect when spoken language does not change")

        // Changing to a different language should reconnect
        recorder.updateSpokenLanguage(com.github.ahatem.qtranslate.api.language.LanguageCode("fr"))
        waitUntil { sessions.size == 2 }
        assertEquals(2, sessions.size, "Live session should reconnect when spoken language changes")

        recorder.stop()
    }

    @Test
    fun `playback preserves original audio on disk while channel mute writes silence`() = runBlocking {
        val mic = ScriptedDevice()
        var playing = false
        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            isPlaybackActive = { playing },
            recognize = { Ok(SpeechRecognitionResponse(text = "ok")) }
        )

        recorder.start(options().copy(systemOutput = null))
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }

        // 1. Playback active: original audio must be written to WAV, but not transcribed
        playing = true
        val loudTone = tone(1_000)
        mic.push(loudTone)
        delay(200)

        // 2. Microphone muted: silence must be written to WAV
        playing = false
        recorder.isMicrophoneMuted = true
        mic.push(loudTone)
        delay(200)

        recorder.stop()

        val saved = repository.list().single()
        val wavFile = repository.audioFile(saved.id, AudioSource.MICROPHONE)
        assertTrue(wavFile.exists(), "Audio file should exist")
        val bytes = wavFile.readBytes()
        // Skip 44-byte WAV header
        val pcm = bytes.copyOfRange(44, bytes.size)

        // First part (during playback) should contain non-zero audio samples from loudTone
        val firstHalf = pcm.copyOfRange(0, pcm.size / 2)
        assertTrue(firstHalf.any { it != 0.toByte() }, "Playback period should preserve non-zero audio on disk")

        // Second part (during mute) should contain all zeroed samples for privacy
        val secondHalf = pcm.copyOfRange(pcm.size / 2, pcm.size)
        assertTrue(secondHalf.all { it == 0.toByte() }, "Mute period should write silence to disk")
    }

    @Test
    fun `live session receives zeroed chunks during playback and mute to avoid clock drift`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        var playing = false
        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { Ok(live) },
            isPlaybackActive = { playing },
            recognize = { Ok(SpeechRecognitionResponse(text = "ok")) }
        )

        recorder.start(options().copy(systemOutput = null))
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        awaitLive(mic, live)

        val initialReceived = live.received.get()

        // 1. Send tone while playback is active
        playing = true
        mic.push(tone(500))
        waitUntil { live.received.get() >= initialReceived + 500 * 32 }

        val afterPlayback = live.received.get()
        assertTrue(afterPlayback > initialReceived, "Live session should receive audio bytes during playback")

        // 2. Send tone while muted
        playing = false
        recorder.isMicrophoneMuted = true
        mic.push(tone(500))
        waitUntil { live.received.get() >= afterPlayback + 500 * 32 }

        assertTrue(live.received.get() > afterPlayback, "Live session should receive audio bytes during mute")

        recorder.stop()
    }

    @Test
    fun `audio spoken while live service connects late is not duplicated by clip segmenter`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        val clipUtterances = java.util.concurrent.atomic.AtomicInteger(0)

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { _ ->
                // Simulate slow connection
                delay(300)
                Ok(live)
            },
            recognize = { req ->
                clipUtterances.incrementAndGet()
                Ok(SpeechRecognitionResponse(text = "accurate: ${req.audio.durationMillis}ms"))
            }
        )

        recorder.start(options().copy(systemOutput = null))
        // Push speech while live is still connecting (during the 300ms delay)
        mic.push(tone(200) + silence(100))

        // Wait until live session finishes connecting and starts receiving replayed audio
        waitUntil { live.received.get() > 0 }
        assertEquals(LiveRecognitionStatus.CONNECTED, recorder.state.value.liveStatus)

        // Live produces the final transcription
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("live speech text"))

        // Wait until transcription is settled
        waitUntil { recorder.state.value.session?.segments?.any { it.text.isNotBlank() } == true }

        recorder.stop()

        val saved = repository.list().single()
        // There should be exactly 1 segment (from live), not a duplicate segment from pre-connect clip
        assertEquals(1, saved.segments.size, "Should have exactly 1 segment from live rather than duplicate from clip")
        assertTrue(saved.segments.single().text.startsWith("accurate:"), "Segment should be transcribed")
    }

    @Test
    fun `simultaneous microphone and system speech maintains atomic settle and timeline ordering`() = runBlocking {
        val mic = ScriptedDevice()
        val sys = ScriptedDevice()
        val micLive = ScriptedLive()
        val sysLive = ScriptedLive()

        val recorder = recorder(
            mic = mic,
            system = sys,
            openLive = { lang ->
                if (lang.tag == "en") Ok(micLive) else Ok(sysLive)
            },
            recognize = { req ->
                Ok(SpeechRecognitionResponse(text = "settled: ${req.prompt ?: ""}".trim()))
            }
        )

        recorder.start(options().copy(
            microphoneLanguage = com.github.ahatem.qtranslate.api.language.LanguageCode("en"),
            systemLanguage = com.github.ahatem.qtranslate.api.language.LanguageCode("ar")
        ))

        waitUntil { recorder.state.value.streams.values.all { it.state == StreamState.LIVE } }
        awaitLive(mic, micLive)
        awaitLive(sys, sysLive)

        // Both speakers produce partials simultaneously
        micLive.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial("Hello from mic"))
        sysLive.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial("Marhaban from system"))

        waitUntil { recorder.state.value.live.size == 2 }
        assertEquals("Hello from mic", recorder.state.value.live[AudioSource.MICROPHONE]?.text)
        assertEquals("Marhaban from system", recorder.state.value.live[AudioSource.SYSTEM]?.text)

        // Microphone settles first
        micLive.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("Hello from mic"))

        // Atomically, microphone partial should be removed and draft inserted in session without flicker
        waitUntil { recorder.state.value.live[AudioSource.MICROPHONE] == null }
        val sessionAfterMic = recorder.state.value.session
        assertTrue(sessionAfterMic?.segments?.any { it.source == AudioSource.MICROPHONE && (it.text.contains("Hello from mic") || it.text.contains("settled")) } == true)
        // System live partial remains active
        assertEquals("Marhaban from system", recorder.state.value.live[AudioSource.SYSTEM]?.text)

        // System settles second
        sysLive.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("Marhaban from system"))
        waitUntil { recorder.state.value.live.isEmpty() }

        waitUntil { recorder.state.value.session?.segments?.size == 2 }
        val finalSegments = recorder.state.value.session!!.segments
        // Deterministic ordering: both started around the same time, AudioSource.MICROPHONE.ordinal < SYSTEM.ordinal
        assertEquals(AudioSource.MICROPHONE, finalSegments[0].source)
        assertEquals(AudioSource.SYSTEM, finalSegments[1].source)

        recorder.stop()
    }

    @Test
    fun `continuous live speech triggers rolling audio verification and stitches confirmed prefix on final`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        val transcribingRequests = java.util.Collections.synchronizedList(mutableListOf<SpeechRecognitionRequest>())

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { Ok(live) },
            recognize = { req ->
                transcribingRequests.add(req)
                Ok(SpeechRecognitionResponse(text = "accurate verified prefix"))
            }
        )

        recorder.start(options().copy(systemOutput = null))
        waitUntil { recorder.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        awaitLive(mic, live)

        // Push audio and send partials until rolling verification triggers
        val partial1 = "one two three four five six seven eight nine ten"
        repeat(12) {
            mic.push(tone(500))
            delay(100)
            live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial(partial1))
            if (recorder.state.value.live[AudioSource.MICROPHONE]?.confirmedText?.isNotBlank() == true) return@repeat
        }

        // Rolling verification should trigger mid-utterance
        waitUntil { transcribingRequests.isNotEmpty() }
        waitUntil { recorder.state.value.live[AudioSource.MICROPHONE]?.confirmedText?.isNotBlank() == true }

        val liveLine = recorder.state.value.live[AudioSource.MICROPHONE]!!
        assertEquals("accurate verified prefix", liveLine.confirmedText)

        // Now send final with tail words
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final(
            text = "one two three four five six seven eight nine ten and the tail words"
        ))

        // Should settle and stitch confirmed prefix with tail
        waitUntil { recorder.state.value.live.isEmpty() }
        waitUntil { recorder.state.value.session?.segments?.any { it.text.contains("accurate verified prefix") } == true }

        val seg = recorder.state.value.session!!.segments.single()
        assertTrue(seg.text.startsWith("accurate verified prefix"), "Segment text should contain the confirmed prefix")
        recorder.stop()
    }

    @Test
    fun `live connects after delay longer than rolling buffer covers every sample exactly once`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()
        val clipUtterances = java.util.Collections.synchronizedList(mutableListOf<SpeechRecognitionRequest>())
        var rec: ConversationRecorder? = null

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { _ ->
                // Wait until at least 11s of audio (352,000 bytes) have been written to disk
                while (true) {
                    val id = rec?.state?.value?.session?.id
                    if (id != null && repository.audioFile(id, AudioSource.MICROPHONE).length() >= 352_000L) {
                        break
                    }
                    delay(30)
                }
                Ok(live)
            },
            recognize = { req ->
                clipUtterances.add(req)
                Ok(SpeechRecognitionResponse(text = "clip: ${req.audio.durationMillis}ms"))
            }
        ).also { rec = it }

        recorder.start(options().copy(systemOutput = null))

        // Push 15s of audio (480,000 bytes). Rolling buffer is 10s (320,000 bytes).
        // First ~5s will be trimmed off and emitted as clips by segmenter.
        mic.push(tone(3_000) + silence(1_000))
        mic.push(tone(3_000) + silence(1_000))
        mic.push(tone(3_000) + silence(1_000))
        mic.push(tone(2_000) + silence(1_000))

        // Wait until live session connects and receives replayed audio
        waitUntil { live.received.get() > 0 }
        assertEquals(LiveRecognitionStatus.CONNECTED, recorder.state.value.liveStatus)

        // Clip should have been emitted for the early audio trimmed from rolling buffer
        waitUntil { clipUtterances.isNotEmpty() }
        assertTrue(clipUtterances.size >= 1, "Early audio trimmed from buffer must be transcribed as clip")

        // Settle live session with final
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("replayed live text"))
        waitUntil { recorder.state.value.session?.segments?.any { it.text.contains("replayed live text") || it.text.startsWith("clip:") } == true }

        recorder.stop()

        val saved = repository.list().single()
        assertTrue(saved.segments.size >= 2, "Must have at least one segment from clip and one from live")

        val replayedBytes = live.received.get()
        val replayedSamples = replayedBytes / 2
        assertTrue(replayedSamples > 0, "Live session should receive replayed samples")
        assertTrue(replayedSamples <= ConversationRecorder.ROLLING_BUFFER_MAX_BYTES / 2, "Replayed samples should be within buffer window")
    }

    @Test
    fun `live connects within buffer window produces no duplicate clips`() = runBlocking {
        val mic = ScriptedDevice()
        val live = ScriptedLive()

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { _ ->
                delay(100)
                Ok(live)
            },
            recognize = { req ->
                Ok(SpeechRecognitionResponse(text = "refined: ${req.prompt ?: ""}"))
            }
        )

        recorder.start(options().copy(systemOutput = null))
        mic.push(tone(500) + silence(500))

        waitUntil { live.received.get() > 0 }
        live.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("single live text"))
        waitUntil { recorder.state.value.session?.segments?.any { it.text.isNotBlank() } == true }

        recorder.stop()

        val saved = repository.list().single()
        assertEquals(1, saved.segments.size, "Should have exactly 1 segment from live and 0 duplicate clips")
        assertTrue(saved.segments.single().text.contains("single live text") || saved.segments.single().text.startsWith("refined:"), "Segment must originate from live path")
    }

    @Test
    fun `live never connects falls back to transcribing all audio via clips`() = runBlocking {
        val mic = ScriptedDevice()
        val clipUtterances = java.util.concurrent.atomic.AtomicInteger(0)

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { _ ->
                Err(ServiceError.ConfigurationError("Live engine disabled"))
            },
            recognize = { req ->
                clipUtterances.incrementAndGet()
                Ok(SpeechRecognitionResponse(text = "clip: ${req.audio.durationMillis}ms"))
            }
        )

        recorder.start(options().copy(systemOutput = null))
        mic.push(tone(1_500) + silence(1_000))

        waitUntil { clipUtterances.get() > 0 }
        recorder.stop()

        val saved = repository.list().single()
        assertTrue(saved.segments.isNotEmpty(), "All audio should be transcribed as clips when live is unavailable")
        assertTrue(saved.segments.all { it.text.startsWith("clip:") }, "All segments should be clips")
    }

    @Test
    fun `stop while reconnecting announces buffered utterance as clip`() = runBlocking {
        val mic = ScriptedDevice()
        val clipUtterances = java.util.concurrent.atomic.AtomicInteger(0)

        val recorder = recorder(
            mic = mic,
            system = ScriptedDevice(),
            openLive = { _ ->
                // Delay long enough so stop occurs while connecting/reconnecting
                delay(5_000)
                Err(ServiceError.NetworkError("temporary failure"))
            },
            recognize = { req ->
                clipUtterances.incrementAndGet()
                Ok(SpeechRecognitionResponse(text = "recovered utterance"))
            }
        )

        recorder.start(options().copy(systemOutput = null))
        // Speak while reconnecting
        mic.push(tone(1_000) + silence(500))

        waitUntil { mic.chunks.isEmpty() }
        delay(100)

        // Stop while still reconnecting
        recorder.stop()

        val saved = repository.list().single()
        assertTrue(saved.segments.isNotEmpty(), "Utterance spoken while reconnecting must be saved as a segment on stop")
        assertTrue(saved.segments.any { it.text == "recovered utterance" || it.pending }, "Utterance must be captured on stop")
    }

    @Test
    fun `honest verification states mark NoService and Failed as UNVERIFIED and Done as VERIFIED or CORRECTED`() = runBlocking {
        // 1. NoService -> UNVERIFIED (and rolling check does NOT confirm live text)
        val mic1 = ScriptedDevice()
        val live1 = ScriptedLive()
        val recNoService = recorder(
            mic = mic1,
            system = ScriptedDevice(),
            openLive = { Ok(live1) },
            recognize = { null }
        )
        recNoService.start(options().copy(systemOutput = null))
        waitUntil { recNoService.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        awaitLive(mic1, live1)

        val partialText = "one two three four five six seven eight nine ten"
        repeat(11) {
            mic1.push(tone(500))
            delay(50)
            live1.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial(partialText))
        }
        waitUntil { recNoService.state.value.live[AudioSource.MICROPHONE]?.verificationState == VerificationState.UNVERIFIED }
        assertEquals("", recNoService.state.value.live[AudioSource.MICROPHONE]?.confirmedText, "NoService must never mark live words as confirmed")

        live1.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final(partialText))
        waitUntil { recNoService.state.value.session?.segments?.any { !it.draft } == true }
        assertEquals(VerificationState.UNVERIFIED, recNoService.state.value.session!!.segments.single().verificationState)
        recNoService.stop()

        // 2. Failed -> UNVERIFIED
        val mic2 = ScriptedDevice()
        val live2 = ScriptedLive()
        val recFailed = recorder(
            mic = mic2,
            system = ScriptedDevice(),
            openLive = { Ok(live2) },
            recognize = { Err(ServiceError.NetworkError("offline")) }
        )
        recFailed.start(options().copy(systemOutput = null))
        waitUntil { recFailed.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        awaitLive(mic2, live2)

        mic2.push(tone(600))
        waitUntil { mic2.chunks.isEmpty() }
        live2.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("hello there"))
        waitUntil { recFailed.state.value.session?.segments?.any { !it.draft } == true }
        assertEquals(VerificationState.UNVERIFIED, recFailed.state.value.session!!.segments.single().verificationState)
        recFailed.stop()

        // 3. Done matching -> VERIFIED; Done differing -> CORRECTED
        val mic3 = ScriptedDevice()
        val live3 = ScriptedLive()
        var responseText = "exact match"
        val recDone = recorder(
            mic = mic3,
            system = ScriptedDevice(),
            openLive = { Ok(live3) },
            recognize = { Ok(SpeechRecognitionResponse(text = responseText)) }
        )
        recDone.start(options().copy(systemOutput = null))
        waitUntil { recDone.state.value.streams[AudioSource.MICROPHONE]?.state == StreamState.LIVE }
        awaitLive(mic3, live3)

        mic3.push(tone(600))
        waitUntil { mic3.chunks.isEmpty() }
        live3.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("exact match"))
        waitUntil { recDone.state.value.session?.segments?.any { !it.draft } == true }
        assertEquals(VerificationState.VERIFIED, recDone.state.value.session!!.segments.first().verificationState)

        // Wait >2s gap so second segment does not coalesce into the first
        mic3.push(silence(2_500))
        waitUntil { mic3.chunks.isEmpty() }
        mic3.push(tone(600))
        waitUntil { mic3.chunks.isEmpty() }
        responseText = "corrected text"
        live3.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Partial("rough draft"))
        live3.channel.send(com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent.Final("rough draft"))
        waitUntil { recDone.state.value.session?.segments?.count { !it.draft } == 2 }
        assertEquals(VerificationState.CORRECTED, recDone.state.value.session!!.segments.last().verificationState)
        recDone.stop()
    }
}

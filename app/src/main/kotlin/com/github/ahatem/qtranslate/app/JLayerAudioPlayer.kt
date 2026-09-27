package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import javazoom.jl.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat.Encoding
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** One blocking output operation. Closing it interrupts playback. */
internal interface AudioOutput {
    fun play(shouldContinue: () -> Boolean)
    fun close()
}

internal fun interface AudioOutputFactory {
    fun create(format: AudioFormat, bytes: ByteArray): AudioOutput
}

internal object SystemAudioOutputFactory : AudioOutputFactory {
    override fun create(format: AudioFormat, bytes: ByteArray): AudioOutput = when (format) {
        AudioFormat.MP3 -> Mp3Output(bytes)
        AudioFormat.WAV -> WavOutput(bytes)
        else -> error("Unsupported audio format: $format")
    }

    private class Mp3Output(bytes: ByteArray) : AudioOutput {
        private val player = Player(ByteArrayInputStream(bytes))
        override fun play(shouldContinue: () -> Boolean) { if (shouldContinue()) player.play() }
        override fun close() = player.close()
    }

    private class WavOutput(bytes: ByteArray) : AudioOutput {
        private val stream: AudioInputStream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        private val line: SourceDataLine

        init {
            try {
                val format = stream.format
                require(format.encoding == Encoding.PCM_SIGNED || format.encoding == Encoding.PCM_UNSIGNED) {
                    "Only PCM WAV is supported"
                }
                line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
                line.open(format)
            } catch (exception: Exception) {
                stream.close()
                throw exception
            }
        }

        override fun play(shouldContinue: () -> Boolean) {
            line.start()
            val buffer = ByteArray(8192)
            while (shouldContinue()) {
                val count = stream.read(buffer)
                if (count < 0) break
                var offset = 0
                while (offset < count && shouldContinue()) {
                    offset += line.write(buffer, offset, count - offset)
                }
            }
            if (shouldContinue()) line.drain()
        }

        override fun close() {
            try { line.stop(); line.flush() } finally {
                try { line.close() } finally { stream.close() }
            }
        }
    }
}

/** Plays MP3 with JLayer and PCM WAV with Java Sound. */
class JLayerAudioPlayer internal constructor(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val outputFactory: AudioOutputFactory,
) : AudioPlayer {
    constructor(scope: CoroutineScope, logger: Logger) : this(scope, logger, SystemAudioOutputFactory)

    private val lock = Any()
    private var generation = 0L
    private var playbackJob: Job? = null
    private var currentOutput: AudioOutput? = null

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    override fun play(audio: TTSAudio.Bytes) {
        if (audio.format != AudioFormat.MP3 && audio.format != AudioFormat.WAV) {
            logger.warn("Audio player only supports MP3 and WAV. Received " + audio.format)
            return
        }
        val token = synchronized(lock) {
            generation++
            playbackJob?.cancel()
            closeCurrent()
            generation
        }
        val job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = outputFactory.create(audio.format, audio.data)
                    if (!register(token, output)) {
                        output.close()
                        return@withContext
                    }
                    output.play { shouldContinue(token) }
                }
            } catch (exception: Exception) {
                if (currentCoroutineContext().isActive && isCurrent(token)) {
                    logger.error("Error during audio playback", exception)
                }
            } finally {
                synchronized(lock) {
                    if (generation == token) {
                        closeCurrent()
                        playbackJob = null
                    }
                }
            }
        }
        synchronized(lock) {
            if (generation == token) playbackJob = job else job.cancel()
        }
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            playbackJob?.cancel()
            playbackJob = null
            closeCurrent()
        }
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private fun shouldContinue(token: Long): Boolean =
        !Thread.currentThread().isInterrupted && isCurrent(token)

    private fun register(token: Long, output: AudioOutput): Boolean = synchronized(lock) {
        if (generation != token) false else {
            currentOutput = output
            _isPlaying.value = true
            true
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }

    private fun closeCurrent() {
        _isPlaying.value = false
        currentOutput?.let { output ->
            currentOutput = null
            runCatching { output.close() }.onFailure { logger.error("Error closing audio output", it) }
        }
    }
}

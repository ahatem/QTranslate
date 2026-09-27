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
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** Plays MP3 with JLayer and PCM WAV with Java Sound. */
class JLayerAudioPlayer(
    private val scope: CoroutineScope,
    private val logger: Logger,
) : AudioPlayer {
    private val lock = Any()
    private var generation = 0L
    private var playbackJob: Job? = null
    private var currentPlayer: Player? = null
    private var currentLine: SourceDataLine? = null

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
            synchronized(lock) {
                if (generation == token) _isPlaying.value = true
            }
            try {
                withContext(Dispatchers.IO) {
                    when (audio.format) {
                        AudioFormat.MP3 -> playMp3(audio.data, token)
                        AudioFormat.WAV -> playWav(audio.data, token)
                        else -> Unit
                    }
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

    private fun playMp3(data: ByteArray, token: Long) {
        val player = Player(ByteArrayInputStream(data))
        if (!register(token) { currentPlayer = player }) {
            player.close()
            return
        }
        player.play()
    }

    private suspend fun playWav(data: ByteArray, token: Long) {
        AudioSystem.getAudioInputStream(ByteArrayInputStream(data)).use { stream ->
            val format = stream.format
            val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
            line.open(format)
            if (!register(token) { currentLine = line }) {
                line.close()
                return
            }
            line.start()
            val buffer = ByteArray(8192)
            while (currentCoroutineContext().isActive && isCurrent(token)) {
                val count = stream.read(buffer)
                if (count < 0) break
                var offset = 0
                while (offset < count && currentCoroutineContext().isActive && isCurrent(token)) {
                    offset += line.write(buffer, offset, count - offset)
                }
            }
            if (currentCoroutineContext().isActive && isCurrent(token)) line.drain()
        }
    }

    private fun register(token: Long, set: () -> Unit): Boolean = synchronized(lock) {
        if (generation != token) false else {
            set()
            true
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }

    private fun closeCurrent() {
        _isPlaying.value = false
        currentLine?.let { line ->
            currentLine = null
            runCatching { line.stop(); line.flush(); line.close() }
        }
        currentPlayer?.let { player ->
            currentPlayer = null
            runCatching { player.close() }.onFailure { logger.error("Error closing JLayer player", it) }
        }
    }
}

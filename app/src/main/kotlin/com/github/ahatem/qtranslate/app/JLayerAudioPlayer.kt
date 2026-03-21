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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * [com.github.ahatem.qtranslate.core.audio.AudioPlayer] implementation backed by the JLayer MP3 decoding library.
 *
 * ### Format support
 * JLayer only decodes MP3. Attempting to play [com.github.ahatem.qtranslate.api.tts.AudioFormat.WAV] or [com.github.ahatem.qtranslate.api.tts.AudioFormat.OGG]
 * is a no-op with a warning log — it will not throw or produce garbage output.
 *
 * ### Concurrency model
 * Each [play] call cancels any in-flight playback job and starts a new one immediately.
 * There is no queue — the most recently requested audio always wins.
 * [isPlaying] is updated atomically around the playback lifecycle.
 *
 * @property scope Coroutine scope for background playback. The caller owns this scope
 *   and is responsible for its lifecycle. [close] will cancel it.
 * @property logger Logger for playback errors and format warnings.
 */
class JLayerAudioPlayer(
    private val scope: CoroutineScope,
    private val logger: Logger
) : AudioPlayer {

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    // The currently running playback job. Cancelled on each new play() call
    // and on stop()/close(). Guarded by @Volatile for visibility across threads.
    @Volatile private var playbackJob: Job? = null

    // The JLayer Player instance for the current playback. Closed when
    // the playback job is cancelled or finishes naturally.
    @Volatile private var currentPlayer: Player? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    override fun play(audio: TTSAudio.Bytes) {
        if (audio.format != AudioFormat.MP3) {
            logger.warn(
                "JLayerAudioPlayer only supports MP3. " +
                        "Received ${audio.format} — ignoring playback request."
            )
            return
        }

        // Cancel the current playback job immediately — new audio always wins.
        // stopInternal() is called inside the new job's finally block via the
        // previous job's cancellation, so we don't double-close here.
        playbackJob?.cancel()

        playbackJob = scope.launch {
            _isPlaying.value = true
            try {
                val player = Player(ByteArrayInputStream(audio.data))
                currentPlayer = player
                withContext(Dispatchers.IO) {
                    player.play()
                }
            } catch (e: Exception) {
                logger.error("Error during audio playback", e)
            } finally {
                // Always clear state, regardless of how playback ended —
                // normal completion, exception, or coroutine cancellation.
                stopInternal()
            }
        }
    }

    override fun stop() {
        playbackJob?.cancel()
        stopInternal()
    }

    override fun close() {
        // Stop playback first — scope.cancel() after, not before.
        // Calling stop() after scope.cancel() would launch a coroutine on a
        // cancelled scope and never execute.
        stopInternal()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Closes the current JLayer [javazoom.jl.player.Player] and resets playback state.
     * Safe to call from any context — does not launch coroutines.
     * Idempotent: safe to call multiple times.
     */
    private fun stopInternal() {
        _isPlaying.value = false
        currentPlayer?.let { player ->
            currentPlayer = null
            runCatching { player.close() }.onFailure { e ->
                logger.error("Error closing JLayer player", e)
            }
        }
    }
}
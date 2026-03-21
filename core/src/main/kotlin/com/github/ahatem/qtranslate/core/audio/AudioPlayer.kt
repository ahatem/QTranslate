package com.github.ahatem.qtranslate.core.audio

import com.github.ahatem.qtranslate.api.tts.TTSAudio
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays synthesized audio produced by a [com.github.ahatem.qtranslate.api.tts.TextToSpeech] service.
 *
 * Accepts [TTSAudio.Bytes] directly so implementations can inspect [TTSAudio.Bytes.format]
 * and either handle it or reject it with a clear error — preventing silent garbage output
 * when a format is unsupported (e.g. a JLayer-based player receiving OGG data).
 *
 * ### Lifecycle
 * Call [close] when the player is no longer needed to release all underlying resources.
 * After [close], behaviour of other methods is undefined.
 *
 * ### Threading
 * All methods are safe to call from any thread. [play] is non-blocking — playback
 * runs on a background thread and [isPlaying] reflects the current state reactively.
 */
interface AudioPlayer {

    /**
     * `true` while audio is actively playing, `false` otherwise.
     * Backed by a [StateFlow] so UI components can collect it directly.
     */
    val isPlaying: StateFlow<Boolean>

    /**
     * Starts playing [audio]. If audio is already playing, it is stopped immediately
     * and replaced by the new audio — there is no queue.
     *
     * This is a non-blocking call. Playback runs in the background;
     * observe [isPlaying] to know when it finishes.
     *
     * @param audio The audio bytes and format to play. Implementations should validate
     *   [TTSAudio.Bytes.format] and log a warning (or no-op) for unsupported formats.
     */
    fun play(audio: TTSAudio.Bytes)

    /**
     * Stops any currently playing audio immediately.
     * No-op if nothing is playing.
     */
    fun stop()

    /**
     * Releases all resources held by this player.
     * Any ongoing playback is stopped first.
     */
    fun close()
}
package com.github.ahatem.qtranslate.core.conversation

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Cuts a continuous 16-bit mono PCM stream into utterances at natural pauses.
 *
 * Speech services transcribe whole clips, so a conversation has to be handed over in pieces, and
 * a piece that ends mid-word comes back garbled. Cutting where the speaker pauses keeps words and
 * usually whole sentences intact.
 *
 * Speech is told from silence by loudness against an adaptive noise floor rather than a fixed
 * threshold: a fan, a hissing virtual cable or a quiet microphone each sit at a different level,
 * and the floor follows whatever the background is.
 *
 * Every utterance carries the sample offset it starts at, counted from the first sample ever
 * passed in, so its position on the recording's timeline is exact.
 */
class SpeechSegmenter(
    private val sampleRate: Int = PcmStream.SAMPLE_RATE,
    private val silenceToEndMillis: Int = 800,
    private val minSpeechMillis: Int = 300,
    private val maxUtteranceMillis: Int = 20_000,
    private val preRollMillis: Int = 250
) {
    /** A finished stretch of speech. */
    class Utterance(val startSample: Long, val pcm: ByteArray) {
        val sampleCount: Int get() = pcm.size / 2
    }

    private val frameSamples = sampleRate * FRAME_MILLIS / 1000
    private val frameBytes = frameSamples * 2

    private var pending = ByteArray(0)
    var position: Long = 0L
        private set

    private var noiseFloor = INITIAL_NOISE_FLOOR
    private val preRoll = ArrayDeque<ByteArray>()
    private val preRollFrames = (preRollMillis / FRAME_MILLIS).coerceAtLeast(1)

    private var current: ByteArrayOutputStream? = null
    private var currentStart = 0L
    private var voicedFrames = 0
    private var silentFrames = 0
    private var onsetFrames = 0

    /** Where the utterance now in progress started (pre-roll included), or null between utterances. */
    val activeStartSample: Long? get() = if (current != null) currentStart else null

    /** Loudness of the most recent frame, 0..1, for a level meter. */
    var level: Float = 0f
        private set

    /** Feeds the next chunk of the stream and returns every utterance it completed. */
    fun accept(pcm: ByteArray): List<Utterance> {
        val data = if (pending.isEmpty()) pcm else pending + pcm
        val completed = ArrayList<Utterance>(1)
        var offset = 0
        while (offset + frameBytes <= data.size) {
            val frame = data.copyOfRange(offset, offset + frameBytes)
            processFrame(frame)?.let(completed::add)
            offset += frameBytes
            position += frameSamples
        }
        pending = data.copyOfRange(offset, data.size)
        return completed
    }

    /** Ends the stream, returning the utterance in progress if it held enough speech. */
    fun flush(): Utterance? = finish()

    fun reset(newPosition: Long = 0L) {
        current = null
        voicedFrames = 0
        silentFrames = 0
        onsetFrames = 0
        preRoll.clear()
        pending = ByteArray(0)
        position = newPosition
    }

    /** Discards any utterance currently in progress without emitting it (e.g. during in-app playback). */
    fun resetUtterance() {
        current = null
        voicedFrames = 0
        silentFrames = 0
        onsetFrames = 0
        preRoll.clear()
    }

    private fun processFrame(frame: ByteArray): Utterance? {
        val rms = rms(frame)
        level = (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
        val isSpeech = rms > maxOf(MIN_SPEECH_RMS, noiseFloor * SPEECH_TO_NOISE_RATIO)

        // The floor drops quickly to a quieter background and rises slowly, so speech itself
        // never drags the threshold up while someone is talking.
        noiseFloor = if (rms < noiseFloor) noiseFloor * 0.9 + rms * 0.1
        else if (!isSpeech) noiseFloor * 0.995 + rms * 0.005
        else noiseFloor

        val utterance = current
        if (utterance == null) {
            preRoll.addLast(frame)
            if (preRoll.size > preRollFrames) preRoll.removeFirst()
            onsetFrames = if (isSpeech) onsetFrames + 1 else 0
            // Two loud frames in a row, so a click or a keystroke does not open an utterance.
            if (onsetFrames >= ONSET_FRAMES) {
                currentStart = position + frameSamples - preRoll.size.toLong() * frameSamples
                current = ByteArrayOutputStream().also { out -> preRoll.forEach(out::write) }
                preRoll.clear()
                voicedFrames = onsetFrames
                silentFrames = 0
                onsetFrames = 0
            }
            return null
        }

        utterance.write(frame)
        if (isSpeech) {
            voicedFrames++
            silentFrames = 0
        } else {
            silentFrames++
        }

        val lengthMillis = utterance.size() / 2 * 1000L / sampleRate
        return when {
            silentFrames * FRAME_MILLIS >= silenceToEndMillis -> finish()
            lengthMillis >= maxUtteranceMillis -> finish()
            else -> null
        }
    }

    private fun finish(): Utterance? {
        val utterance = current ?: return null
        current = null
        silentFrames = 0
        val enough = voicedFrames * FRAME_MILLIS >= minSpeechMillis
        voicedFrames = 0
        return if (enough) Utterance(currentStart, utterance.toByteArray()) else null
    }

    private fun rms(frame: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < frame.size) {
            val sample = ((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += sample * sample
            i += 2
        }
        return sqrt(sum / (frame.size / 2).coerceAtLeast(1))
    }

    private companion object {
        const val FRAME_MILLIS = 30
        const val ONSET_FRAMES = 2
        const val SPEECH_TO_NOISE_RATIO = 3.0
        /** Below this nothing counts as speech, however silent the background (~-50 dBFS). */
        const val MIN_SPEECH_RMS = 100.0
        const val INITIAL_NOISE_FLOOR = 150.0
    }
}

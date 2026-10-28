package com.github.ahatem.qtranslate.core.conversation

import kotlin.math.roundToInt

/**
 * Turns 16-bit little-endian PCM at any rate and channel count into mono at [targetRate].
 *
 * Devices rarely record at 16 kHz mono — Stereo Mix and virtual cables usually offer 44.1 or
 * 48 kHz stereo only — while speech services want exactly that. Channels are averaged and the
 * rate changed by linear interpolation, which is plenty for speech: everything a recogniser uses
 * sits well below 8 kHz.
 *
 * Stateful: the interpolation position and the last sample carry over between [process] calls,
 * so a stream fed in arbitrary chunks comes out without clicks at the joins.
 */
class MonoResampler(
    private val sourceRate: Int,
    private val channels: Int,
    private val targetRate: Int = PcmStream.SAMPLE_RATE
) {
    init {
        require(sourceRate > 0) { "Source rate must be positive, was $sourceRate." }
        require(channels > 0) { "Channel count must be positive, was $channels." }
    }

    private val step = sourceRate.toDouble() / targetRate
    private var position = 0.0
    private var previous = 0.0
    private var hasPrevious = false

    private val weights: DoubleArray = when (channels) {
        1 -> doubleArrayOf(1.0)
        2 -> doubleArrayOf(0.5, 0.5)
        // 5.1 surround (FL, FR, Center, LFE, RL, RR): emphasize dialogue/center, eliminate bass LFE mud
        6 -> doubleArrayOf(0.25, 0.25, 0.40, 0.0, 0.05, 0.05)
        // 7.1 surround (FL, FR, Center, LFE, RL, RR, SL, SR): emphasize speech, reduce surround/subwoofer
        8 -> doubleArrayOf(0.20, 0.20, 0.35, 0.0, 0.05, 0.05, 0.075, 0.075)
        else -> DoubleArray(channels) { 1.0 / channels }
    }

    /** Converts the first [length] bytes of [input]; a trailing partial frame is ignored. */
    fun process(input: ByteArray, length: Int = input.size): ByteArray {
        val frameBytes = channels * 2
        val frames = length / frameBytes
        if (frames == 0) return ByteArray(0)

        val mono = DoubleArray(frames) { frame ->
            var sum = 0.0
            for (channel in 0 until channels) {
                val offset = frame * frameBytes + channel * 2
                val low = input[offset].toInt() and 0xFF
                val high = input[offset + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toDouble()
                sum += sample * weights[channel]
            }
            sum
        }

        if (sourceRate == targetRate) return toBytes(mono)

        // Index -1 stands for the last sample of the previous chunk, so interpolation can cross
        // the boundary.
        fun sampleAt(index: Int): Double = if (index < 0) previous else mono[index]

        val out = ArrayList<Double>((frames / step).toInt() + 2)
        if (!hasPrevious) {
            previous = mono[0]
            hasPrevious = true
        }
        while (position < frames - 1) {
            val base = kotlin.math.floor(position).toInt()
            val fraction = position - base
            val a = sampleAt(base)
            val b = sampleAt(base + 1)
            out += a + (b - a) * fraction
            position += step
        }
        position -= frames
        previous = mono[frames - 1]
        return toBytes(out.toDoubleArray())
    }

    private fun toBytes(samples: DoubleArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            val value = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
        return bytes
    }
}

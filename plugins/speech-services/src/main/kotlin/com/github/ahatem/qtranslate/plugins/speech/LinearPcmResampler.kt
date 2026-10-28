package com.github.ahatem.qtranslate.plugins.speech

import kotlin.math.roundToInt

/**
 * Resamples 16-bit mono little-endian PCM from [sourceRate] to [targetRate] using linear interpolation.
 *
 * Stateful across calls to [process] so chunk boundaries do not produce clicks or phase drift.
 */
class LinearPcmResampler(
    private val sourceRate: Int = 16_000,
    private val targetRate: Int = 24_000
) {
    init {
        require(sourceRate > 0) { "sourceRate must be positive, was $sourceRate" }
        require(targetRate > 0) { "targetRate must be positive, was $targetRate" }
    }

    private val step = sourceRate.toDouble() / targetRate
    private var position = 0.0
    private var previous = 0.0
    private var hasPrevious = false

    fun process(input: ByteArray, length: Int = input.size): ByteArray {
        val frames = length / 2
        if (frames == 0) return ByteArray(0)

        val mono = DoubleArray(frames) { frame ->
            val offset = frame * 2
            val low = input[offset].toInt() and 0xFF
            val high = input[offset + 1].toInt()
            ((high shl 8) or low).toShort().toDouble()
        }

        if (sourceRate == targetRate) return input.copyOf(length)

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

        val bytes = ByteArray(out.size * 2)
        for (i in out.indices) {
            val value = out[i].roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i * 2] = value.toByte()
            bytes[i * 2 + 1] = (value shr 8).toByte()
        }
        return bytes
    }
}

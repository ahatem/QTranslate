package com.github.ahatem.qtranslate.plugins.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearPcmResamplerTest {

    @Test
    fun `resampling 16kHz to 24kHz increases samples by 1_5x`() {
        val resampler = LinearPcmResampler(sourceRate = 16_000, targetRate = 24_000)
        // 1600 samples of 16-bit mono PCM = 3200 bytes (100 ms at 16 kHz)
        val input = ByteArray(3_200) { i -> (i % 128).toByte() }
        val output = resampler.process(input)

        // 100 ms at 24 kHz = 2400 samples = 4800 bytes
        // Resampler can have +/- 1 sample at boundaries depending on fractional stepping
        val outputFrames = output.size / 2
        assertTrue(outputFrames in 2398..2402, "Expected ~2400 frames, got $outputFrames")
    }

    @Test
    fun `empty input produces empty output`() {
        val resampler = LinearPcmResampler(sourceRate = 16_000, targetRate = 24_000)
        val output = resampler.process(ByteArray(0))
        assertEquals(0, output.size)
    }

    @Test
    fun `identity rate returns input copy`() {
        val resampler = LinearPcmResampler(sourceRate = 16_000, targetRate = 16_000)
        val input = byteArrayOf(1, 2, 3, 4)
        val output = resampler.process(input)
        assertTrue(input.contentEquals(output))
    }
}

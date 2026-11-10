package com.github.ahatem.qtranslate.core.conversation

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic speech: a 220 Hz tone loud enough to count as voice. */
internal fun tone(millis: Int, amplitude: Int = 8_000, rate: Int = PcmStream.SAMPLE_RATE): ByteArray {
    val samples = rate * millis / 1000
    val buffer = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(samples) { i -> buffer.putShort((amplitude * sin(2 * PI * 220 * i / rate)).toInt().toShort()) }
    return buffer.array()
}

internal fun silence(millis: Int, rate: Int = PcmStream.SAMPLE_RATE): ByteArray = ByteArray(rate * millis / 1000 * 2)

class AudioProcessingTest {

    @Test
    fun `segmenter cuts at a pause and places the utterance on the timeline`() {
        val segmenter = SpeechSegmenter()
        val stream = silence(1_000) + tone(1_200) + silence(1_000) + tone(800) + silence(1_000)

        // Fed in uneven chunks, as a device delivers them.
        val utterances = stream.toList().chunked(1_234).flatMap { segmenter.accept(it.toByteArray()) }

        assertEquals(2, utterances.size)
        val firstStartMillis = utterances[0].startSample * 1000 / PcmStream.SAMPLE_RATE
        // Starts within the pre-roll before the tone at 1000 ms.
        assertTrue(firstStartMillis in 700..1_000, "first utterance started at $firstStartMillis ms")
        val secondStartMillis = utterances[1].startSample * 1000 / PcmStream.SAMPLE_RATE
        assertTrue(secondStartMillis in 2_900..3_200, "second utterance started at $secondStartMillis ms")
    }

    @Test
    fun `segmenter ignores clicks shorter than the minimum speech length`() {
        val segmenter = SpeechSegmenter()
        val utterances = segmenter.accept(silence(500) + tone(90) + silence(1_500))
        assertTrue(utterances.isEmpty())
        assertEquals(null, segmenter.flush())
    }

    @Test
    fun `segmenter splits a long monologue at the maximum length`() {
        val segmenter = SpeechSegmenter(maxUtteranceMillis = 2_000)
        val utterances = segmenter.accept(tone(5_000)) + listOfNotNull(segmenter.flush())
        assertEquals(3, utterances.size)
        assertTrue(utterances.all { it.sampleCount <= PcmStream.SAMPLE_RATE * 2 + 480 })
    }

    @Test
    fun `segmenter adapts to a steady background hiss`() {
        val segmenter = SpeechSegmenter()
        val hiss = tone(3_000, amplitude = 400)
        assertTrue(segmenter.accept(hiss).isEmpty(), "constant low noise must not count as speech")
        val utterances = segmenter.accept(tone(1_000, amplitude = 6_000) + tone(1_500, amplitude = 400))
        assertEquals(1, utterances.size)
    }

    @Test
    fun `resampler turns 48 kHz stereo into 16 kHz mono of the same length`() {
        val resampler = MonoResampler(sourceRate = 48_000, channels = 2)
        val stereo = ByteBuffer.allocate(48_000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48_000) { stereo.putShort(1_000).putShort(3_000) }
        val bytes = stereo.array()

        val out = bytes.toList().chunked(4 * 1_001).fold(ByteArray(0)) { acc, chunk ->
            acc + resampler.process(chunk.toByteArray())
        }

        val samples = out.size / 2
        assertTrue(samples in 15_990..16_010, "got $samples samples for one second")
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2_000, buffer.getShort(out.size / 2 / 2 * 2).toInt(), "channels are averaged")
    }

    @Test
    fun `wav writer produces a readable file and ranges read back exactly`() {
        val dir = createTempDirectory("wav").toFile()
        val file = File(dir, "a.wav")
        WavFileWriter(file).use { writer ->
            writer.write(silence(500))
            writer.write(tone(1_000))
        }
        assertEquals(1_500, WavFiles.durationMillis(file))
        val stream = javax.sound.sampled.AudioSystem.getAudioInputStream(file)
        assertEquals(24_000, stream.frameLength)
        assertEquals(PcmStream.SAMPLE_RATE * 2 / 2, WavFiles.readRange(file, 500, 1_000).size)
        dir.deleteRecursively()
    }

    @Test
    fun `repair restores the sizes of a file cut off before its header was refreshed`() {
        val dir = createTempDirectory("wav").toFile()
        val file = File(dir, "a.wav")
        val writer = WavFileWriter(file)
        writer.write(tone(300)) // less than a second: header never refreshed
        // Simulates a crash: the file is abandoned without close().
        file.appendBytes(ByteArray(0))
        WavFiles.repair(file)
        assertEquals(300, WavFiles.durationMillis(file))
        val stream = javax.sound.sampled.AudioSystem.getAudioInputStream(file)
        assertEquals(4_800, stream.frameLength)
        writer.close()
        dir.deleteRecursively()
    }
}

package com.github.ahatem.qtranslate.core.conversation

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/**
 * Records through `javax.sound.sampled`.
 *
 * Java Sound has no WASAPI loopback, so "what the computer is playing" is only reachable through
 * a device that exposes it as an input: Windows' Stereo Mix, or a virtual cable (VB-Cable,
 * VoiceMeeter) that Discord's output is routed to. Those show up here like any microphone.
 */
class JavaSoundAudioBackend : AudioBackend {

    override fun inputDevices(): List<AudioDevice> =
        AudioSystem.getMixerInfo()
            .filter { info -> AudioSystem.getMixer(info).canRecord() }
            // Windows' primary capture driver is only an alias for whichever device is the default.
            .filterNot { it.name.startsWith("Primary Sound Capture") }
            .map { AudioDevice(id = it.name, name = it.name) }
            .distinctBy { it.id }

    override fun open(device: AudioDevice): PcmStream {
        // Names are not unique: SteelSeries Sonar, for one, has an output and an input both called
        // "Sonar - Microphone". Only the one that can record is wanted.
        var lastError: Exception? = null
        for (attempt in 1..4) {
            val mixer = AudioSystem.getMixerInfo()
                .filter { it.name == device.id }
                .map(AudioSystem::getMixer)
                .firstOrNull { it.canRecord() }

            if (mixer != null) {
                for (format in CANDIDATE_FORMATS) {
                    val info = DataLine.Info(TargetDataLine::class.java, format)
                    if (!runCatching { mixer.isLineSupported(info) }.getOrDefault(false)) continue
                    val line = try {
                        (mixer.getLine(info) as TargetDataLine).apply { open(format, bufferBytes(format)) }
                    } catch (e: Exception) {
                        lastError = e
                        continue
                    }
                    line.start()
                    return JavaSoundPcmStream(line, format)
                }
            }
            if (attempt < 4) Thread.sleep(250)
        }
        throw AudioDeviceUnavailableException(device, lastError)
    }

    private fun bufferBytes(format: AudioFormat): Int =
        (format.frameRate * format.frameSize).toInt() // one second, so a slow consumer never drops audio

    private class JavaSoundPcmStream(
        private val line: TargetDataLine,
        format: AudioFormat
    ) : PcmStream {
        private val resampler = MonoResampler(format.sampleRate.toInt(), format.channels)

        // ~100 ms per read: small enough for snappy pause detection, large enough to keep the
        // per-call overhead negligible.
        private val chunk = ByteArray(((format.frameRate / 10).toInt() * format.frameSize))

        @Volatile
        private var closed = false

        override fun read(): ByteArray? {
            if (closed) return null
            val count = line.read(chunk, 0, chunk.size)
            if (closed || count <= 0) return if (closed) null else ByteArray(0)
            return resampler.process(chunk, count)
        }

        override fun close() {
            closed = true
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    private companion object {
        /** Tried in order; the first a device accepts wins. 16 kHz mono needs no conversion. */
        val CANDIDATE_FORMATS = listOf(16_000f, 48_000f, 44_100f, 32_000f, 22_050f).flatMap { rate ->
            listOf(1, 2).map { channels -> AudioFormat(rate, 16, channels, true, false) }
        }
    }
}

private fun Mixer.canRecord(): Boolean =
    runCatching { isLineSupported(DataLine.Info(TargetDataLine::class.java, null)) }.getOrDefault(false)

package com.github.ahatem.qtranslate.core.conversation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Plays short stretches of a recording: a segment or a single word. Starting one stops whatever
 * was playing, so clicking through a transcript never overlaps voices.
 */
class SegmentPlayer {
    private val _playing = MutableStateFlow<Any?>(null)

    /** The tag passed to [play] for what is playing now, or null. */
    val playing: StateFlow<Any?> = _playing.asStateFlow()

    @Volatile
    private var generation = 0

    /** Plays 16-bit mono PCM at [PcmStream.SAMPLE_RATE]. Returns false when no output is available. */
    fun play(pcm: ByteArray, tag: Any? = null): Boolean {
        if (pcm.isEmpty()) return false
        val format = AudioFormat(PcmStream.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val line = runCatching { AudioSystem.getSourceDataLine(format).apply { open(format) } }.getOrNull() ?: return false
        val mine = ++generation
        _playing.value = tag
        Thread({ playOn(line, pcm, mine) }, "conversation-playback").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        generation++
        _playing.value = null
    }

    private fun playOn(line: SourceDataLine, pcm: ByteArray, mine: Int) {
        try {
            line.start()
            var offset = 0
            while (offset < pcm.size && generation == mine) {
                val length = minOf(CHUNK_BYTES, pcm.size - offset)
                offset += line.write(pcm, offset, length)
            }
            if (generation == mine) line.drain() else line.flush()
        } finally {
            line.close()
            if (generation == mine) _playing.value = null
        }
    }

    private companion object {
        const val CHUNK_BYTES = 3_200 // 100 ms, so a stop is heard almost at once
    }
}

package com.github.ahatem.qtranslate.core.conversation

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit mono PCM WAV files: encoding clips, writing recordings incrementally and reading ranges
 * back for playback.
 */
object WavFiles {
    const val HEADER_SIZE = 44

    /** A complete WAV file holding [pcm]. */
    fun encode(pcm: ByteArray, sampleRate: Int = PcmStream.SAMPLE_RATE): ByteArray =
        header(pcm.size, sampleRate) + pcm

    /**
     * Rewrites [file]'s size fields from its length on disk.
     *
     * A recording cut short by a crash or power loss keeps its audio but carries the sizes from
     * the last periodic update; players then stop early or refuse the file. Safe on a healthy file.
     */
    fun repair(file: File, sampleRate: Int = PcmStream.SAMPLE_RATE) {
        if (!file.isFile || file.length() < HEADER_SIZE) return
        RandomAccessFile(file, "rw").use { raf ->
            val dataBytes = ((raf.length() - HEADER_SIZE) / 2 * 2).toInt()
            raf.seek(0)
            raf.write(header(dataBytes, sampleRate))
        }
    }

    /** Duration of the PCM in [file], in milliseconds. */
    fun durationMillis(file: File, sampleRate: Int = PcmStream.SAMPLE_RATE): Long =
        ((file.length() - HEADER_SIZE).coerceAtLeast(0) / 2) * 1000 / sampleRate

    /** The PCM between [startMillis] and [endMillis] of [file], clamped to what exists. */
    fun readRange(
        file: File,
        startMillis: Long,
        endMillis: Long,
        sampleRate: Int = PcmStream.SAMPLE_RATE
    ): ByteArray {
        if (!file.isFile) return ByteArray(0)
        RandomAccessFile(file, "r").use { raf ->
            val available = (raf.length() - HEADER_SIZE).coerceAtLeast(0)
            val from = (startMillis.coerceAtLeast(0) * sampleRate / 1000 * 2).coerceAtMost(available)
            val to = (endMillis * sampleRate / 1000 * 2).coerceIn(from, available)
            val bytes = ByteArray((to - from).toInt())
            raf.seek(HEADER_SIZE + from)
            raf.readFully(bytes)
            return bytes
        }
    }

    private fun header(dataBytes: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)              // PCM chunk size
            putShort(1)             // PCM
            putShort(1)             // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)  // byte rate
            putShort(2)             // block align
            putShort(16)            // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
}

/**
 * Appends PCM to a WAV file as it is recorded, so a long session never sits in memory and a crash
 * loses at most the last second.
 *
 * The header's sizes are refreshed about once a second rather than on every write; [WavFiles.repair]
 * fixes the remainder after an interruption.
 */
class WavFileWriter(val file: File, private val sampleRate: Int = PcmStream.SAMPLE_RATE) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(WavFiles.encode(ByteArray(0), sampleRate))
    }
    private var dataBytes = 0L
    private var bytesSinceHeader = 0L

    /** Samples written so far. */
    val sampleCount: Long get() = dataBytes / 2

    @Synchronized
    fun write(pcm: ByteArray) {
        raf.seek(WavFiles.HEADER_SIZE + dataBytes)
        raf.write(pcm)
        dataBytes += pcm.size
        bytesSinceHeader += pcm.size
        if (bytesSinceHeader >= sampleRate * 2L) {
            refreshHeader()
            bytesSinceHeader = 0
        }
    }

    @Synchronized
    override fun close() {
        runCatching { refreshHeader() }
        raf.close()
    }

    private fun refreshHeader() {
        raf.seek(0)
        raf.write(WavFiles.encode(ByteArray(0), sampleRate).also { header ->
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(4, (36 + dataBytes).toInt())
                putInt(40, dataBytes.toInt())
            }
        })
    }
}

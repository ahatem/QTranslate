package com.github.ahatem.qtranslate.core.conversation

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Selects safe pause boundaries between words (or low-energy frames when word timings are absent)
 * for rolling audio verification, and filters out context overlap words from transcription results.
 */
object VerificationSlicer {

    const val MIN_PAUSE_GAP_MILLIS: Long = 150L
    const val WINDOW_MIN_MILLIS: Long = 4_500L
    const val WINDOW_MAX_MILLIS: Long = 6_000L
    const val CONTEXT_OVERLAP_MILLIS: Long = 500L
    private const val MIN_SLICE_MILLIS: Long = 1_500L

    data class CutDecision(
        /** Session timestamp (ms) where the verification slice ends (inside the pause after [lastWordIndex]). */
        val cutMillis: Long,
        /** Total number of words from the start of the utterance included up to this cut. */
        val totalWordsIncluded: Int,
        /** The words belonging to the current slice `(sliceStartMillis .. cutMillis]`. */
        val sliceWords: List<TranscriptWord>
    )

    /**
     * Picks a cut point at the end of a word whose following gap is at least [minGapMillis],
     * nearest to the `[targetMinMillis, targetMaxMillis]` window relative to [sliceStartMillis].
     * Never cuts inside a word.
     */
    fun chooseWordBoundaryCut(
        words: List<TranscriptWord>,
        sliceStartMillis: Long,
        currentEndMillis: Long,
        targetMinMillis: Long = WINDOW_MIN_MILLIS,
        targetMaxMillis: Long = WINDOW_MAX_MILLIS,
        minGapMillis: Long = MIN_PAUSE_GAP_MILLIS
    ): CutDecision? {
        if (words.isEmpty()) return null

        data class Candidate(
            val index: Int,
            val word: TranscriptWord,
            val gapMillis: Long,
            val sliceDurationMillis: Long,
            val distanceToWindow: Long
        )

        val candidates = ArrayList<Candidate>()
        for (i in words.indices) {
            val w = words[i]
            if (w.endMillis <= sliceStartMillis) continue
            val sliceDuration = w.endMillis - sliceStartMillis
            if (sliceDuration < MIN_SLICE_MILLIS) continue

            val nextStart = if (i + 1 < words.size) words[i + 1].startMillis else currentEndMillis
            val gap = (nextStart - w.endMillis).coerceAtLeast(0L)

            val dist = when {
                sliceDuration < targetMinMillis -> targetMinMillis - sliceDuration
                sliceDuration > targetMaxMillis -> sliceDuration - targetMaxMillis
                else -> 0L
            }
            candidates += Candidate(i, w, gap, sliceDuration, dist)
        }

        if (candidates.isEmpty()) return null

        val windowCenter = (targetMinMillis + targetMaxMillis) / 2
        val pauseCandidates = candidates.filter { it.gapMillis >= minGapMillis }
        val chosen = if (pauseCandidates.isNotEmpty()) {
            pauseCandidates.minWithOrNull(
                compareBy<Candidate> { it.distanceToWindow }
                    .thenBy { abs(it.sliceDurationMillis - windowCenter) }
                    .thenByDescending { it.gapMillis }
            )!!
        } else if (currentEndMillis - sliceStartMillis >= targetMaxMillis) {
            // Forced by max window: still never cut inside a word; pick the boundary with largest gap near window
            candidates.maxWithOrNull(
                compareBy<Candidate> { it.gapMillis }
                    .thenBy { -it.distanceToWindow }
            )!!
        } else {
            return null
        }

        val sliceWords = words.subList(0, chosen.index + 1).filter { it.endMillis > sliceStartMillis }
        val cutMillis = chosen.word.endMillis + (chosen.gapMillis / 2).coerceAtMost(60L)
        return CutDecision(
            cutMillis = cutMillis,
            totalWordsIncluded = chosen.index + 1,
            sliceWords = sliceWords
        )
    }

    /**
     * Fallback when the live recognizer does not provide word timestamps: scans 20 ms frames
     * across `[searchStartSample, searchEndSample]` and returns the sample at the quietest frame.
     */
    fun findLowEnergyCutSample(
        pcm: ByteArray,
        bufferStartSample: Long,
        searchStartSample: Long,
        searchEndSample: Long,
        sampleRate: Int = PcmStream.SAMPLE_RATE
    ): Long {
        val frameSamples = (sampleRate * 20) / 1000 // 20 ms = 320 samples at 16 kHz
        val frameBytes = frameSamples * 2
        if (pcm.size < frameBytes) return searchEndSample

        val relStartSamples = (searchStartSample - bufferStartSample).coerceAtLeast(0L).toInt()
        val relEndSamples = (searchEndSample - bufferStartSample).coerceAtLeast(relStartSamples.toLong()).toInt()

        val startByte = (relStartSamples * 2).coerceIn(0, (pcm.size - frameBytes).coerceAtLeast(0))
        val endByte = (relEndSamples * 2).coerceIn(startByte, pcm.size - frameBytes)

        var bestByteOffset = endByte
        var minRms = Double.MAX_VALUE

        var offset = startByte
        while (offset <= endByte) {
            val rms = frameRms(pcm, offset, frameBytes)
            if (rms <= minRms) {
                minRms = rms
                bestByteOffset = offset + frameBytes / 2
            }
            offset += frameBytes
        }

        return bufferStartSample + (bestByteOffset / 2).toLong()
    }

    private fun frameRms(pcm: ByteArray, startByte: Int, byteLength: Int): Double {
        val count = byteLength / 2
        if (count <= 0) return 0.0
        var sumSq = 0.0
        var i = startByte
        val limit = (startByte + byteLength).coerceAtMost(pcm.size - 1)
        while (i < limit) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val s = sample.toShort().toDouble()
            sumSq += s * s
            i += 2
        }
        return sqrt(sumSq / count)
    }

    /**
     * Drops words from an accurate transcription result whose midpoint falls inside the
     * pre-cut context overlap (`< sliceStartMillis`), so context overlap words are never duplicated.
     */
    fun filterOutOverlapWords(
        words: List<TranscriptWord>,
        fallbackText: String,
        sliceStartMillis: Long
    ): Pair<String, List<TranscriptWord>> {
        if (words.isEmpty()) {
            return fallbackText.trim() to emptyList()
        }
        val kept = words.filter { word ->
            val midpoint = (word.startMillis + word.endMillis) / 2
            midpoint >= sliceStartMillis && word.endMillis > sliceStartMillis + 30L
        }
        val text = kept.joinToString(" ") { it.text.trim() }.trim()
        return text to kept
    }
}

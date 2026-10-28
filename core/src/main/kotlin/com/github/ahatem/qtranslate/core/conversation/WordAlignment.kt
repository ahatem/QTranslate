package com.github.ahatem.qtranslate.core.conversation

/**
 * Aligns live recognition words against accurate recognition words using word-level edit distance
 * with case, punctuation, and Arabic orthographic normalization.
 */
object WordAlignment {

    data class Result(
        val text: String,
        val words: List<TranscriptWord>,
        val matchedCount: Int,
        val liveWordCount: Int,
        val accurateWordCount: Int,
        val agreement: Double,
        val state: VerificationState
    )

    /**
     * Normalizes a token for comparison: strips punctuation, lowercases, removes Arabic tatweel
     * and diacritics (tashkeel), and unifies Alef (`أ إ آ ٱ` -> `ا`), Ya/Alef Maqsura (`ى` -> `ي`),
     * and Ta-Marbuta (`ة` -> `ه`).
     */
    fun normalizeToken(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '\u0640' -> continue // Tatweel
                in '\u064B'..'\u065F', '\u0670', in '\u06D6'..'\u06ED' -> continue // Arabic diacritics
                '\u0623', '\u0625', '\u0622', '\u0671' -> sb.append('\u0627') // Alef variants -> ا
                '\u0649' -> sb.append('\u064A') // Alef Maqsura ى -> ي
                '\u0629' -> sb.append('\u0647') // Ta Marbuta ة -> ه
                else -> if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun tokenize(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    /**
     * Aligns [liveText] against [accurateText] (and optional timed [accurateWords]).
     * Matching words receive [VerificationState.VERIFIED]; substituted or inserted words receive
     * [VerificationState.CORRECTED] with the accurate text; deleted words mark the segment [VerificationState.CORRECTED].
     */
    fun align(
        liveText: String,
        accurateText: String,
        accurateWords: List<TranscriptWord> = emptyList(),
        defaultStartMillis: Long = 0L,
        defaultEndMillis: Long = 0L
    ): Result {
        val liveTokens = tokenize(liveText)
        val accTokens = if (accurateWords.isNotEmpty()) {
            accurateWords.map { it.text.trim() }.filter { it.isNotBlank() }
        } else {
            tokenize(accurateText)
        }

        val n = liveTokens.size
        val m = accTokens.size

        if (n == 0 && m == 0) {
            return Result(
                text = "",
                words = emptyList(),
                matchedCount = 0,
                liveWordCount = 0,
                accurateWordCount = 0,
                agreement = 1.0,
                state = VerificationState.VERIFIED
            )
        }

        val timedAccWords: List<TranscriptWord> = if (accurateWords.size == m && m > 0) {
            accurateWords
        } else {
            val totalDur = (defaultEndMillis - defaultStartMillis).coerceAtLeast(m.toLong())
            accTokens.mapIndexed { idx, tok ->
                val wStart = defaultStartMillis + (totalDur * idx) / m.coerceAtLeast(1)
                val wEnd = defaultStartMillis + (totalDur * (idx + 1)) / m.coerceAtLeast(1)
                TranscriptWord(text = tok, startMillis = wStart, endMillis = wEnd)
            }
        }

        if (n == 0 || m == 0) {
            val outWords = timedAccWords.map { it.copy(verificationState = VerificationState.CORRECTED) }
            return Result(
                text = accTokens.joinToString(" "),
                words = outWords,
                matchedCount = 0,
                liveWordCount = n,
                accurateWordCount = m,
                agreement = 0.0,
                state = VerificationState.CORRECTED
            )
        }

        val normLive = liveTokens.map(::normalizeToken)
        val normAcc = accTokens.map(::normalizeToken)

        // dp[i][j] = min edit distance between normLive[0 until i] and normAcc[0 until j]
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j

        for (i in 1..n) {
            for (j in 1..m) {
                val same = normLive[i - 1].isNotEmpty() && normLive[i - 1] == normAcc[j - 1]
                val subCost = dp[i - 1][j - 1] + if (same) 0 else 1
                val delCost = dp[i - 1][j] + 1
                val insCost = dp[i][j - 1] + 1
                dp[i][j] = minOf(subCost, delCost, insCost)
            }
        }

        val alignedWords = ArrayList<TranscriptWord>(m)
        var matched = 0
        var hadDeletion = false
        var i = n
        var j = m
        var markAdjacentCorrected = false

        while (i > 0 || j > 0) {
            val same = i > 0 && j > 0 && normLive[i - 1].isNotEmpty() && normLive[i - 1] == normAcc[j - 1]
            when {
                i > 0 && j > 0 && same && dp[i][j] == dp[i - 1][j - 1] -> {
                    val wState = if (markAdjacentCorrected) {
                        markAdjacentCorrected = false
                        VerificationState.CORRECTED
                    } else {
                        VerificationState.VERIFIED
                    }
                    alignedWords.add(timedAccWords[j - 1].copy(verificationState = wState))
                    matched++
                    i--
                    j--
                }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    markAdjacentCorrected = false
                    alignedWords.add(timedAccWords[j - 1].copy(verificationState = VerificationState.CORRECTED))
                    i--
                    j--
                }
                j > 0 && (i == 0 || dp[i][j] == dp[i][j - 1] + 1) -> {
                    markAdjacentCorrected = false
                    alignedWords.add(timedAccWords[j - 1].copy(verificationState = VerificationState.CORRECTED))
                    j--
                }
                else -> {
                    hadDeletion = true
                    if (alignedWords.isNotEmpty()) {
                        val lastIdx = alignedWords.lastIndex
                        alignedWords[lastIdx] = alignedWords[lastIdx].copy(verificationState = VerificationState.CORRECTED)
                    } else {
                        markAdjacentCorrected = true
                    }
                    i--
                }
            }
        }

        alignedWords.reverse()
        val denom = maxOf(n, m)
        val agreement = matched.toDouble() / denom.toDouble()
        val overallState = if (matched == n && n == m && !hadDeletion) {
            VerificationState.VERIFIED
        } else {
            VerificationState.CORRECTED
        }

        return Result(
            text = alignedWords.joinToString(" ") { it.text },
            words = alignedWords,
            matchedCount = matched,
            liveWordCount = n,
            accurateWordCount = m,
            agreement = agreement,
            state = overallState
        )
    }
}

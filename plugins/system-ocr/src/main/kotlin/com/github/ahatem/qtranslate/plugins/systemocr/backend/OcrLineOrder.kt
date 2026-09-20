package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * Rebuilds a line's logical reading order from the words `Windows.Media.Ocr` returns.
 *
 * The engine lists a line's words in visual order, left to right on the page, and `OcrLine.Text` is
 * those words joined in that order. That matches logical order only for left-to-right text; an
 * Arabic line comes back with its words reversed.
 *
 * Words are reordered by direction, never reversed character by character. They are sorted by
 * geometry, classified with [Character.getDirectionality], split into runs, and for a right-to-left
 * line the order of the runs is reversed while left-to-right runs keep their internal order. Numbers
 * are kept distinct from neutral words so `Windows 11` stays together, and a left-to-right line is
 * returned from the engine untouched, which also keeps the spacing of scripts that do not separate
 * words.
 */
internal object OcrLineOrder {

    /**
     * A word's bidirectional class, reduced to what the reordering needs. Numbers are kept apart
     * from [NEUTRAL] because they resolve differently.
     */
    private enum class Direction {
        LTR,
        RTL,
        EUROPEAN_NUMBER,
        ARABIC_NUMBER,
        NEUTRAL,
    }

    /** The logical text of one recognized line. */
    fun lineText(line: HelperLine, languageHint: LanguageCode): String {
        val words = line.words.sortedWith(compareBy({ it.x }, { it.y }))
        if (words.isEmpty()) return line.text

        val base = baseDirection(words, languageHint)

        // Nothing to reorder, and this keeps the engine's original spacing.
        if (base != Direction.RTL) return line.text

        val resolved = resolveNeutrals(resolveNumbers(words.map { directionOf(it.t) }, base), base)
        return runs(words, resolved).asReversed()
            .flatMap { run -> if (run.direction == Direction.RTL) run.words.asReversed() else run.words }
            .joinToString(" ") { it.t }
    }

    /**
     * The line's base direction, taken from the recognized content rather than the request. The
     * language hint only breaks the tie when the two ends of the line disagree, because a line laid
     * out right-to-left and one laid out left-to-right can produce the same words in the same places.
     */
    private fun baseDirection(words: List<HelperWord>, languageHint: LanguageCode): Direction {
        val strong = words.map { directionOf(it.t) }.filter { it.isStrong() }

        if (strong.isEmpty()) return Direction.LTR
        if (strong.none { it == Direction.RTL }) return Direction.LTR
        if (strong.none { it == Direction.LTR }) return Direction.RTL

        val leftmost = strong.first()
        val rightmost = strong.last()
        if (leftmost == rightmost) return leftmost

        if (languageHint.isRightToLeftScript()) return Direction.RTL
        return if (strong.count { it == Direction.RTL } > strong.count { it == Direction.LTR }) {
            Direction.RTL
        } else {
            Direction.LTR
        }
    }

    /**
     * The bidirectional class of a word, from the first character in it that has one. Numbers,
     * separators and terminators are skipped while looking, so `+1` classifies as a number.
     */
    private fun directionOf(word: String): Direction {
        var index = 0
        while (index < word.length) {
            val codePoint = word.codePointAt(index)
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return Direction.LTR
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> return Direction.RTL

                Character.DIRECTIONALITY_EUROPEAN_NUMBER -> return Direction.EUROPEAN_NUMBER
                Character.DIRECTIONALITY_ARABIC_NUMBER -> return Direction.ARABIC_NUMBER
            }
            index += Character.charCount(codePoint)
        }
        return Direction.NEUTRAL
    }

    /**
     * Assigns each number to a run. A European number joins a neighbouring left-to-right run (UAX #9
     * W7, plus the even level it keeps after right-to-left text), which is what holds `Windows 11`
     * together inside an Arabic sentence; with no Latin neighbour it stays in the surrounding flow.
     * An Arabic number always stays in the right-to-left flow.
     */
    private fun resolveNumbers(classifications: List<Direction>, base: Direction): List<Direction> {
        val resolved = classifications.toMutableList()
        for (index in resolved.indices) {
            when (resolved[index]) {
                Direction.EUROPEAN_NUMBER ->
                    resolved[index] = if (nearestStrongDirection(resolved, index) == Direction.LTR) {
                        Direction.LTR
                    } else {
                        base
                    }

                Direction.ARABIC_NUMBER -> resolved[index] = base
                else -> Unit
            }
        }
        return resolved
    }

    /** The nearest strong word on either side of [index], looking past numbers and neutral words. */
    private fun nearestStrongDirection(directions: List<Direction>, index: Int): Direction? {
        var left: Direction? = null
        var before = index - 1
        while (before >= 0) {
            if (directions[before].isStrong()) {
                left = directions[before]
                break
            }
            before--
        }

        var right: Direction? = null
        var after = index + 1
        while (after < directions.size) {
            if (directions[after].isStrong()) {
                right = directions[after]
                break
            }
            after++
        }

        // Latin on either side wins; otherwise the number follows the surrounding flow.
        return if (left == Direction.LTR || right == Direction.LTR) Direction.LTR else left ?: right
    }

    /**
     * UAX #9 rule N1 for a word with no direction of its own: it takes the direction shared by the
     * words around it, or the base direction when those differ.
     */
    private fun resolveNeutrals(directions: List<Direction>, base: Direction): List<Direction> {
        val resolved = directions.toMutableList()
        var index = 0
        while (index < resolved.size) {
            if (resolved[index] != Direction.NEUTRAL) {
                index++
                continue
            }
            var end = index
            while (end < resolved.size && resolved[end] == Direction.NEUTRAL) end++

            val before = resolved.getOrNull(index - 1)
            val after = resolved.getOrNull(end)
            val replacement = if (before != null && before == after) before else base
            for (position in index until end) resolved[position] = replacement
            index = end
        }
        return resolved
    }

    private fun runs(words: List<HelperWord>, directions: List<Direction>): List<Run> {
        val runs = mutableListOf<Run>()
        var index = 0
        while (index < words.size) {
            val direction = directions[index]
            var end = index
            while (end < words.size && directions[end] == direction) end++
            runs += Run(direction, words.subList(index, end))
            index = end
        }
        return runs
    }

    private fun Direction.isStrong(): Boolean = this == Direction.LTR || this == Direction.RTL

    private class Run(val direction: Direction, val words: List<HelperWord>)

    private fun LanguageCode.isRightToLeftScript(): Boolean =
        tag.substringBefore('-').lowercase() in RIGHT_TO_LEFT_SCRIPTS

    /** Primary subtags of right-to-left languages, used only to break a tie. */
    private val RIGHT_TO_LEFT_SCRIPTS = setOf(
        "ar", "arc", "ckb", "dv", "fa", "he", "ku", "ps", "sd", "ug", "ur", "yi",
    )
}

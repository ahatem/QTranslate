package com.github.ahatem.qtranslate.plugins.ai

/**
 * Extracts a JSON document from raw model output.
 *
 * Models asked for structured output frequently answer with prose around the
 * JSON, or with the JSON wrapped in a Markdown code fence. Callers get a
 * clean document to hand to their parser — or, when nothing JSON-like is
 * present, the trimmed raw text back so their parse fails loudly instead of
 * silently succeeding on an empty string.
 *
 * No framework is involved: this is string surgery only.
 */
object AiJson {

    /**
     * Returns the JSON document contained in [raw]:
     *
     * 1. Trims whitespace.
     * 2. Strips a ```` ``` ```` fence anywhere in the text, including a
     *    `json` (or any other) language tag and surrounding whitespace.
     * 3. If the result still has surrounding prose, returns the first
     *    balanced `{...}` or `[...]` substring (string literals and escapes
     *    are honoured while scanning).
     * 4. Otherwise returns the trimmed raw text unchanged, so the caller's
     *    parser errors out on malformed output.
     */
    fun extractJson(raw: String): String {
        var text = raw.trim()

        text = extractFenced(text)?.trim()?.takeIf { it.isNotEmpty() } ?: text

        val firstOpener = text.indexOfFirst { it == '{' || it == '[' }
        if (firstOpener < 0) return text

        return balancedSubstring(text, firstOpener) ?: text
    }

    /**
     * If [text] contains a fenced code block, returns its contents: the text
     * between the first ```` ``` ```` (and its optional language-tag line)
     * and the next ```` ``` ````. Returns null when there is no fence.
     */
    private fun extractFenced(text: String): String? {
        val fenceStart = text.indexOf("```")
        if (fenceStart < 0) return null

        val afterFence = fenceStart + 3
        // The rest of the opening line is a language tag (e.g. `json`) unless
        // it already carries content, like ```{"a":1}``` — keep real content.
        val lineEnd = text.indexOf('\n', afterFence).let { if (it < 0) text.length else it }
        val lineRest = text.substring(afterFence, lineEnd).trim()
        val isLanguageTag = lineRest.isEmpty() || LANGUAGE_TAG.matches(lineRest)
        val contentStart = if (isLanguageTag) minOf(lineEnd + 1, text.length) else afterFence

        val fenceEnd = text.indexOf("```", contentStart)
        return if (fenceEnd < 0) {
            // Unterminated fence: take everything after the opening line.
            text.substring(contentStart).trim()
        } else {
            text.substring(contentStart, fenceEnd)
        }
    }

    /**
     * Returns the balanced substring starting at [start] (a `{` or `[`),
     * or null when no matching close bracket exists. Nested brackets and
     * brackets inside string literals are handled.
     */
    private val LANGUAGE_TAG = Regex("[A-Za-z0-9_+\\-]*")

    private fun balancedSubstring(text: String, start: Int): String? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> stack.addLast('}')
                c == '[' -> stack.addLast(']')
                c == '}' || c == ']' -> {
                    if (stack.isEmpty() || stack.removeLast() != c) return null
                    if (stack.isEmpty()) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }
}

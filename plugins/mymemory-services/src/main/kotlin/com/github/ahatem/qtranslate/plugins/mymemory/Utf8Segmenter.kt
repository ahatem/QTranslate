package com.github.ahatem.qtranslate.plugins.mymemory

internal object Utf8Segmenter {
    fun split(text: String, maxBytes: Int): List<String> {
        require(maxBytes > 0)
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes && !containsParagraphBreak(text)) {
            return listOf(text)
        }

        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0

        text.forEachCodePoint { codePoint ->
            val value = String(Character.toChars(codePoint))
            val valueBytes = value.toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && currentBytes + valueBytes > maxBytes) {
                segments += current.toString()
                current.clear()
                currentBytes = 0
            }
            current.append(value)
            currentBytes += valueBytes

            if (value == "\n" && current.endsWith("\n\n")) {
                segments += current.toString()
                current.clear()
                currentBytes = 0
            }
        }
        if (current.isNotEmpty()) segments += current.toString()
        return segments
    }

    private fun containsParagraphBreak(text: String): Boolean =
        text.contains("\n\n") || text.contains("\r\n\r\n")

    private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
        var offset = 0
        while (offset < length) {
            val codePoint = codePointAt(offset)
            action(codePoint)
            offset += Character.charCount(codePoint)
        }
    }
}

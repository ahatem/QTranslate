package com.github.ahatem.qtranslate.core.document.text

internal data class LineContent(val lines: MutableList<String>, val lineEnding: String, val trailingNewline: Boolean) {
    fun render(): String = lines.joinToString(lineEnding) + if (trailingNewline) lineEnding else ""

    companion object {
        fun parse(original: String): LineContent {
            val lineEnding = if ("\r\n" in original) "\r\n" else "\n"
            val trailingNewline = original.endsWith("\n") || original.endsWith("\r")
            val lines = original.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
            if (trailingNewline && lines.lastOrNull()?.isEmpty() == true) lines.removeLast()
            return LineContent(lines, lineEnding, trailingNewline)
        }
    }
}

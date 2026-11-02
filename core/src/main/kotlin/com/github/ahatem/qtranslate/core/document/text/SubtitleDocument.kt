package com.github.ahatem.qtranslate.core.document.text

import com.github.ahatem.qtranslate.core.document.*
import com.github.ahatem.qtranslate.core.document.common.translateIndexed
import com.github.ahatem.qtranslate.api.translator.Translator
import java.io.File
import java.nio.charset.StandardCharsets

internal object SubtitleDocument {
    suspend fun translateSubtitle(
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        timestampPattern: Regex,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        val original = request.inputFile.readText(StandardCharsets.UTF_8)
        val content = LineContent.parse(original)
        val lines = content.lines
        val indexes = mutableListOf<Int>()
        var inCue = false
        for (index in lines.indices) {
            val line = lines[index]
            when {
                timestampPattern.containsMatchIn(line) -> inCue = true
                line.isBlank() -> inCue = false
                inCue && !line.startsWith("NOTE") && !line.startsWith("STYLE") && !line.startsWith("REGION") -> indexes += index
            }
        }

        translateIndexed(
            indexes,
            { index -> stripAndRememberMarkup(lines[index]).text },
            { index, value ->
                val protected = stripAndRememberMarkup(lines[index])
                lines[index] = protected.restore(value)
            },
            request,
            translator,
            onProgress
        )
        output.writeText(content.render(), StandardCharsets.UTF_8)
    }

    private data class ProtectedMarkup(val text: String, val tokens: List<Pair<String, String>>) {
        fun restore(translated: String): String {
            var restored = translated
            tokens.forEach { (token, markup) -> restored = restored.replace(token, markup) }
            return restored
        }
    }

    private fun stripAndRememberMarkup(text: String): ProtectedMarkup {
        val tokens = mutableListOf<Pair<String, String>>()
        var protected = text
        markup.findAll(text).toList().asReversed().forEachIndexed { index, match ->
            val token = "__QT_MARKUP_${index}__"
            tokens += token to match.value
            protected = protected.replaceRange(match.range, token)
        }
        return ProtectedMarkup(protected, tokens)
    }

    private val markup = Regex("<[^>]+>|\\{\\\\[^}]+}")
}

package com.github.ahatem.qtranslate.core.document.text

import com.github.ahatem.qtranslate.core.document.*
import com.github.ahatem.qtranslate.core.document.common.translateIndexed
import com.github.ahatem.qtranslate.api.translator.Translator
import java.io.File
import java.nio.charset.StandardCharsets

internal object TextDocuments {
    suspend fun translatePlainText(
        request: DocumentTranslationRequest,
        output: File,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        val original = request.inputFile.readText(StandardCharsets.UTF_8)
        val content = LineContent.parse(original)
        val lines = content.lines
        val indexes = lines.indices.filter { lines[it].isNotBlank() }

        translateIndexed(indexes, lines::get, { index, value -> lines[index] = value }, request, translator, onProgress)
        output.writeText(content.render(), StandardCharsets.UTF_8)
    }

}

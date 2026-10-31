package com.github.ahatem.qtranslate.core.document.common

import com.github.ahatem.qtranslate.core.document.*
import com.github.ahatem.qtranslate.core.document.common.translateIndexed
import com.github.ahatem.qtranslate.api.translator.BatchTranslationRequest
import com.github.ahatem.qtranslate.api.translator.BatchTranslator
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.michaelbull.result.fold
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

internal suspend fun <T> translateIndexed(
        items: List<T>,
        getText: (T) -> String,
        setText: (T, String) -> Unit,
        request: DocumentTranslationRequest,
        translator: Translator,
        onProgress: (DocumentTranslationProgress) -> Unit
    ) {
        onProgress(DocumentTranslationProgress(0, items.size))
        val segments = items.map { it to getText(it) }.filter { it.second.isNotBlank() }
        val batchTranslator = translator as? BatchTranslator
        if (batchTranslator == null) {
            segments.forEachIndexed { position, (item, source) ->
                coroutineContext.ensureActive()
                setText(item, translateSegment(source, request, translator))
                onProgress(DocumentTranslationProgress(position + 1, segments.size, source.take(80)))
            }
            return
        }

        var completed = 0
        segments.chunkedFor(batchTranslator).forEach { batch ->
            coroutineContext.ensureActive()
            val translated = translateBatch(batch.map { it.second }, request, batchTranslator)
            batch.zip(translated).forEach { (segment, value) ->
                val (item, source) = segment
                setText(item, value)
                completed++
                onProgress(DocumentTranslationProgress(completed, segments.size, source.take(80)))
            }
        }
    }

    private fun <T> List<Pair<T, String>>.chunkedFor(translator: BatchTranslator): List<List<Pair<T, String>>> {
        val batches = mutableListOf<MutableList<Pair<T, String>>>()
        val maxItems = translator.maxBatchSize.coerceAtLeast(1)
        val maxCharacters = translator.maxBatchCharacters.coerceAtLeast(1)
        for (segment in this) {
            val current = batches.lastOrNull()
            val fits = current != null &&
                current.size < maxItems &&
                current.sumOf { it.second.length } + segment.second.length <= maxCharacters
            if (fits) current.add(segment) else batches += mutableListOf(segment)
        }
        return batches
    }

    private suspend fun translateBatch(
        texts: List<String>,
        request: DocumentTranslationRequest,
        translator: BatchTranslator
    ): List<String> {
        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translator.translateBatch(
                BatchTranslationRequest(texts, request.sourceLanguage, request.targetLanguage)
            )
        } ?: throw DocumentTranslationException("Translation timed out while processing the document.")

        return result.fold(
            success = { response ->
                if (response.translations.size != texts.size) {
                    throw DocumentTranslationException(
                        "${translator.name} returned ${response.translations.size} translations for ${texts.size} segments."
                    )
                }
                response.translations.map { it.translatedText }
            },
            failure = { throw DocumentTranslationException(it.message, it) }
        )
    }

    private suspend fun translateSegment(
        text: String,
        request: DocumentTranslationRequest,
        translator: Translator
    ): String {
        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translator.translate(TranslationRequest(text, request.sourceLanguage, request.targetLanguage))
        } ?: throw DocumentTranslationException("Translation timed out while processing the document.")

        return result.fold(
            success = { it.translatedText },
            failure = { throw DocumentTranslationException(it.message, it) }
        )
    }

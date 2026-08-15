package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches a one-line definition to sit under a single-word translation.
 *
 * Separate from [LookupWordUseCase] rather than sharing its state, because the two answer
 * different questions. That one fills a dictionary the user opened and is entitled to take its
 * time and show everything it found. This one is a glance — it runs unasked, so it must be quick,
 * quiet, and easy to ignore, and it must never put an error where the reader expected a
 * translation. Every failure here ends the same way: no definition, and nothing said about it.
 */
class FetchInlineDefinitionUseCase(
    private val scope: CoroutineScope,
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("FetchInlineDefinitionUseCase")
    private var job: Job? = null

    operator fun invoke(
        word: String,
        language: LanguageCode,
        alternateWord: String,
        alternateLanguage: LanguageCode,
        updateState: (MainState.() -> MainState) -> Unit
    ) {
        job?.cancel(CancellationException("New inline definition requested"))
        updateState { copy(inlineDefinition = "") }

        val dictionary = activeServiceManager.getActiveService<Dictionary>(ServiceType.DICTIONARY)
        if (dictionary == null) {
            logger.debug("No dictionary service active — no inline definition")
            return
        }

        val candidates = listOf(word to language, alternateWord to alternateLanguage)
            .filter { (candidate, _) -> candidate.isNotBlank() }
            .distinctBy { (candidate, lang) -> candidate.lowercase() to lang.tag }

        job = scope.launch {
            // Tried in turn, first hit wins. The translated word is asked about first because it
            // is what the reader is looking at; the source word is the fallback because it is
            // usually English, which is the language dictionaries actually cover.
            for ((candidate, candidateLanguage) in candidates) {
                val summary = summarise(dictionary, candidate, candidateLanguage) ?: continue
                logger.debug("Inline definition ready for '$candidate' (${candidateLanguage.tag})")
                updateState { copy(inlineDefinition = summary) }
                return@launch
            }
            logger.debug("No inline definition for ${candidates.joinToString { it.first }}")
        }
    }

    private suspend fun summarise(
        dictionary: Dictionary,
        word: String,
        language: LanguageCode
    ): String? {
        val response = runCatching {
            // Shorter than a normal lookup's patience. This is a detail beside the translation;
            // if it has not arrived by now the reader has already moved on.
            withTimeoutOrNull(TIMEOUT_MS) {
                dictionary.lookup(DictionaryRequest(word, language)).get()
            }
        }.getOrNull() ?: return null

        val entry = response.entries.firstOrNull() ?: return null
        val meanings = entry.definitions.take(MAX_MEANINGS)
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
        if (meanings.isEmpty()) return null

        val partOfSpeech = entry.partOfSpeech?.trim().orEmpty()
        val body = meanings.joinToString(" · ")
        return if (partOfSpeech.isEmpty()) body else "$partOfSpeech — $body"
    }

    /** Clears the line, for when the translation is no longer a single word. */
    fun clear(updateState: (MainState.() -> MainState) -> Unit) {
        job?.cancel(CancellationException("Inline definition no longer applicable"))
        updateState { copy(inlineDefinition = "") }
    }

    private companion object {
        const val TIMEOUT_MS = 6_000L

        /** Two senses is a glance; more is a dictionary, which is what the panel is for. */
        const val MAX_MEANINGS = 2
    }
}

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
        val meanings = entry.definitions
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
        if (meanings.isEmpty()) return null

        val partOfSpeech = entry.partOfSpeech?.trim().orEmpty()
        val body = withinBudget(meanings)
        return if (partOfSpeech.isEmpty()) body else "$partOfSpeech — $body"
    }

    /**
     * Fits the senses into a glance.
     *
     * The first sense always appears, shortened if it runs long. A second is added only when the
     * first was brief enough that both still fit — taking a fixed two senses regardless of length
     * is what turned a one-line note into a paragraph, and something you have to read is a
     * different thing from something you can take in at a glance. Anything past that belongs in
     * the dictionary, which is a keystroke away.
     */
    private fun withinBudget(meanings: List<String>): String {
        val first = elide(meanings.first(), FIRST_SENSE_BUDGET)
        if (meanings.size == 1 || first.length > SECOND_SENSE_THRESHOLD) return first

        val combined = first + separatorAfter(first) + meanings[1]
        return if (combined.length <= TOTAL_BUDGET) combined else first
    }

    /**
     * What goes between two senses.
     *
     * A sense that already ends in punctuation separates itself; putting a bullet after a full
     * stop is clutter, and reads as though the two halves were fragments of one thought rather
     * than two complete ones.
     */
    private fun separatorAfter(previous: String): String =
        if (previous.lastOrNull() in SENTENCE_ENDINGS) " " else " · "

    /** Cut at a word boundary rather than mid-word, and never leave dangling punctuation. */
    private fun elide(text: String, budget: Int): String {
        if (text.length <= budget) return text
        val cut = text.take(budget)
        val boundary = cut.lastIndexOf(' ')
        val kept = if (boundary > budget / 2) cut.take(boundary) else cut
        return kept.trimEnd().trimEnd(',', ';', ':', '،', '؛') + "…"
    }

    /** Clears the line, for when the translation is no longer a single word. */
    fun clear(updateState: (MainState.() -> MainState) -> Unit) {
        job?.cancel(CancellationException("Inline definition no longer applicable"))
        updateState { copy(inlineDefinition = "") }
    }

    private companion object {
        const val TIMEOUT_MS = 6_000L

        /**
         * Budgets in characters rather than pixels.
         *
         * The strip does not know how wide it will be drawn, and the popup and the main window
         * differ anyway. Characters are a coarse proxy, but the decision being made here is only
         * "one sense or two", which does not need pixel accuracy — and a rule that holds in both
         * places is worth more than one tuned for either.
         */
        const val FIRST_SENSE_BUDGET = 120

        /** A first sense longer than this leaves no room for a second worth reading. */
        const val SECOND_SENSE_THRESHOLD = 60

        const val TOTAL_BUDGET = 150

        /** Latin and Arabic sentence endings — both scripts turn up here routinely. */
        val SENTENCE_ENDINGS = setOf('.', '!', '?', '۔', '؟', '…')
    }
}

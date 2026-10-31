package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.dictionary.*
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal data class DictionaryLookupRequest(val word: String, val language: LanguageCode, val targetLanguage: LanguageCode?)
internal data class DictionaryLookupOutcome(
    val status: StatusCode, val type: NotificationType,
    val entries: List<DictionaryEntry>? = null, val failed: Boolean? = null
)

class LookupWordUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("LookupWordUseCase")

    internal suspend operator fun invoke(request: DictionaryLookupRequest, onStarted: suspend () -> Unit): DictionaryLookupOutcome {
        val (word, language, targetLanguage) = request
        if (word.isBlank()) return DictionaryLookupOutcome(StatusCode.NoWordToLookup, NotificationType.WARNING)
        val dictionary = activeServiceManager.getActiveService<Dictionary>(ServiceRole.DICTIONARY)
            ?: return DictionaryLookupOutcome(StatusCode.NoDictionaryServiceActive, NotificationType.ERROR, emptyList(), true)
        return try {
            onStarted()
            val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
                if (targetLanguage != null && dictionary is BilingualDictionary)
                    dictionary.lookupBilingual(BilingualDictionaryRequest(word, language, targetLanguage))
                else dictionary.lookup(DictionaryRequest(word, language))
            } ?: return DictionaryLookupOutcome(StatusCode.DictionaryTimeout, NotificationType.WARNING, emptyList(), true)
            result.fold(
                success = { response ->
                    if (response.entries.isEmpty()) DictionaryLookupOutcome(StatusCode.DictionaryNotFound(word), NotificationType.INFO, emptyList())
                    else DictionaryLookupOutcome(StatusCode.DictionaryReady, NotificationType.INFO, response.entries, false)
                },
                failure = { error -> DictionaryLookupOutcome(StatusCode.DictionaryFailed(error.toString()), NotificationType.ERROR, emptyList(), true) }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "Unknown error"
            logger.warn("Unexpected error during dictionary lookup")
            DictionaryLookupOutcome(StatusCode.DictionaryFailed(message), NotificationType.ERROR, emptyList(), true)
        }
    }
}

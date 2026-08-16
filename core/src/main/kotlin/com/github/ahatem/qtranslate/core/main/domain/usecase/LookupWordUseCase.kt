package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionary
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LookupWordUseCase(
    private val scope: CoroutineScope,
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("LookupWordUseCase")
    private var lookupJob: Job? = null

    suspend operator fun invoke(
        word: String,
        language: LanguageCode,
        targetLanguage: LanguageCode? = null,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (StatusCode, NotificationType, Boolean) -> Unit
    ) {
        lookupJob?.cancel(CancellationException("New lookup requested"))

        if (word.isBlank()) {
            onStatusUpdate(StatusCode.NoWordToLookup, NotificationType.WARNING, true)
            return
        }

        val dictionary = activeServiceManager.getActiveService<Dictionary>(ServiceRole.DICTIONARY)
        if (dictionary == null) {
            logger.warn("No dictionary service available")
            onStatusUpdate(StatusCode.NoDictionaryServiceActive, NotificationType.ERROR, true)
            updateState { copy(isDictionaryLoading = false, dictionaryFailed = true, dictionaryEntries = emptyList()) }
            return
        }

        logger.info("Looking up '$word' with '${dictionary.name}'")

        lookupJob = scope.launch {
            try {
                onStatusUpdate(StatusCode.LookingUpWord, NotificationType.INFO, false)
                // The previous definitions stay up while this one runs. They are still readable,
                // and a popup that empties itself the moment you ask it for something else takes
                // away what you were reading in exchange for a blank panel. They are replaced
                // when the new result lands, and cleared only if it turns out there is nothing
                // to replace them with.
                updateState {
                    copy(
                        isDictionaryLoading = true,
                        dictionaryWord = word,
                        dictionaryLanguage = language,
                        dictionaryFailed = false
                    )
                }

                val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
                    val bilingualRequest = targetLanguage?.let { target ->
                        (dictionary as? BilingualDictionary)?.let { it to target }
                    }
                    if (bilingualRequest != null) {
                        val (bilingual, target) = bilingualRequest
                        bilingual.lookupBilingual(BilingualDictionaryRequest(word, language, target))
                    } else {
                        dictionary.lookup(DictionaryRequest(word, language))
                    }
                }

                if (result == null) {
                    logger.warn("Dictionary lookup timed out for '$word'")
                    onStatusUpdate(StatusCode.DictionaryTimeout, NotificationType.WARNING, true)
                    updateState { copy(isDictionaryLoading = false, dictionaryFailed = true, dictionaryEntries = emptyList()) }
                    return@launch
                }

                result.fold(
                    success = { response ->
                        if (response.entries.isEmpty()) {
                            logger.info("No definitions found for '$word'")
                            onStatusUpdate(StatusCode.DictionaryNotFound(word), NotificationType.INFO, true)
                            updateState { copy(isDictionaryLoading = false, dictionaryEntries = emptyList()) }
                        } else {
                            logger.info("Found ${response.entries.size} entries for '$word'")
                            onStatusUpdate(StatusCode.DictionaryReady, NotificationType.INFO, true)
                            updateState {
                                copy(
                                    isDictionaryLoading = false,
                                    dictionaryEntries = response.entries,
                                    dictionaryFailed = false
                                )
                            }
                        }
                    },
                    failure = { error ->
                        val msg = error.toString()
                        logger.warn("Dictionary lookup failed for '$word': $msg")
                        onStatusUpdate(StatusCode.DictionaryFailed(msg), NotificationType.ERROR, true)
                        updateState { copy(isDictionaryLoading = false, dictionaryFailed = true, dictionaryEntries = emptyList()) }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                logger.warn("Unexpected error during dictionary lookup: $msg")
                onStatusUpdate(StatusCode.DictionaryFailed(msg), NotificationType.ERROR, true)
                updateState { copy(isDictionaryLoading = false, dictionaryFailed = true, dictionaryEntries = emptyList()) }
            }
        }
    }
}

package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.github.ahatem.qtranslate.core.shared.util.shortSummary
import java.util.Locale

class PerformSpellCheckUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory,
    private val userLocale: () -> Locale = { Locale.getDefault() },
) {
    private val logger: Logger = loggerFactory.getLogger("PerformSpellCheckUseCase")
    private val dynamicLanguagesMutex = Mutex()
    private var cachedDynamicChecker: SpellChecker? = null
    private var cachedDynamicLanguages: Set<LanguageCode>? = null

    private companion object {
        const val SPELL_CHECK_TIMEOUT_MS = 10_000L
    }

    /**
     * Spell-checks [text] in the source language from [currentState].
     *
     * Returns an empty list silently when no spell checker is active — spell check
     * is an optional enhancement, not a critical operation, so failures should not
     * interrupt the main translation flow.
     *
     * @param currentState Supplies the selected or detected source language.
     */
    suspend operator fun invoke(
        currentState: MainState,
        text: String,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): List<Correction> {
        if (text.isBlank()) {
            logger.debug("Spell check skipped: text is blank")
            return emptyList()
        }

        val spellChecker = activeServiceManager.getActiveService<SpellChecker>(ServiceRole.SPELL_CHECKER)
        if (spellChecker == null) {
            logger.debug("No spell checker service available — skipping")
            return emptyList()
        }

        val language = resolveSpellCheckLanguage(currentState, spellChecker) ?: return emptyList()
        logger.debug("Performing spell check with '${spellChecker.name}'")
        val request = SpellCheckRequest(text = text, language = language)

        val result = withTimeoutOrNull(SPELL_CHECK_TIMEOUT_MS) {
            spellChecker.check(request)
        }

        if (result == null) {
            logger.warn("Spell check timed out after ${SPELL_CHECK_TIMEOUT_MS}ms")
            onStatusUpdate(StatusCode.SpellCheckTimeout, NotificationType.WARNING, true)
            return emptyList()
        }

        return result.fold(
            success = { response ->
                logger.debug("Spell check found ${response.corrections.size} correction(s)")
                response.corrections
            },
            failure = { error ->
                logger.error("Spell check failed: ${error.message}", error.cause)
                val summary = error.shortSummary()
                onStatusUpdate(StatusCode.SpellCheckFailed(summary), NotificationType.WARNING, true)
                emptyList()
            }
        )
    }

    private suspend fun resolveSpellCheckLanguage(state: MainState, checker: SpellChecker): LanguageCode? {
        if (state.sourceLanguage != LanguageCode.AUTO) return state.sourceLanguage
        val supported = when (val languages = checker.supportedLanguages) {
            SupportedLanguages.All -> return LanguageCode.AUTO
            is SupportedLanguages.Specific -> languages.languages
            SupportedLanguages.Dynamic -> dynamicLanguagesMutex.withLock {
                (if (cachedDynamicChecker === checker) cachedDynamicLanguages else null)
                    ?: checker.fetchSupportedLanguages().fold(
                    success = { codes ->
                        cachedDynamicChecker = checker
                        cachedDynamicLanguages = codes
                        codes
                    },
                    failure = { return@withLock null },
                )
            } ?: return null
        }
        if (LanguageCode.AUTO in supported) return LanguageCode.AUTO
        state.detectedSourceLanguage?.let { return it.takeIf { language -> language in supported } }
        val localeTag = userLocale().toLanguageTag()
        if (localeTag == "und") return null
        supported.firstOrNull { it.tag.equals(localeTag, ignoreCase = true) }?.let { return it }
        val base = localeTag.substringBefore('-')
        return supported.firstOrNull { it.tag.equals(base, ignoreCase = true) }
    }
}

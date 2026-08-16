package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

/**
 * Translates one string, for callers that are not the main window.
 *
 * [TranslateTextUseCase] drives the main translation flow and owns a great deal that only makes
 * sense there: the input pane's text, history, the extra output panel, a cancellable job shared
 * with all of it. The language editor wants none of that. It wants a sentence turned into another
 * language so it can offer the result as a suggestion.
 *
 * Deliberately has no state and no job of its own. Each call stands alone, so a caller can run as
 * many as it likes without one interfering with another.
 */
class TranslateStringUseCase(
    private val activeServiceManager: ActiveServiceManager
) {
    suspend operator fun invoke(
        text: String,
        target: LanguageCode,
        source: LanguageCode = LanguageCode.AUTO
    ): Result<String, ServiceError> {
        val translator = activeServiceManager.getActiveService<Translator>(ServiceRole.TRANSLATOR)
            ?: return Err(ServiceError.ConfigurationError("No translation service is active."))

        return translator
            .translate(TranslationRequest(text = text, sourceLanguage = source, targetLanguage = target))
            .map { it.translatedText }
    }
}

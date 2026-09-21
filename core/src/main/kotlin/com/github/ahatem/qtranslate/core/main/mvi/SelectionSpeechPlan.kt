package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationCompletion
import com.github.ahatem.qtranslate.core.settings.data.SelectionReadSource
import com.github.ahatem.qtranslate.core.settings.data.TextSource

/** Execution-level TTS arguments derived from the selection product preference. */
internal data class SelectionSpeechPlan(
    val textSource: TextSource,
    val textOverride: String,
    val languageOverride: LanguageCode
)

internal fun selectionSpeechPlan(
    readSource: SelectionReadSource,
    selectedText: String,
    completion: TranslationCompletion?,
    state: MainState
): SelectionSpeechPlan? = when (readSource) {
    SelectionReadSource.SOURCE ->
        selectedText.takeIf { it.isNotBlank() }?.let {
            SelectionSpeechPlan(TextSource.Input, it, state.resolvedSourceLanguage)
        }

    SelectionReadSource.TRANSLATION ->
        completion?.translatedText?.takeIf { it.isNotBlank() }?.let {
            SelectionSpeechPlan(TextSource.Output, it, state.targetLanguage)
        }
}

package com.github.ahatem.qtranslate.ui.swing.main.lookup

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

class AutoLookupCoordinator(
    private val mainState: StateFlow<MainState>,
    private val settingsState: StateFlow<SettingsState>,
    private val dispatch: (MainIntent) -> Unit,
    private val isMainVisible: () -> Boolean,
    private val setDictionarySearchWord: (String) -> Unit,
) {
    suspend fun observe() {
        mainState
            .combine(settingsState) { m, s -> m to s }
            .map { (m, s) ->
                AutoLookupKey(
                    panelVisible = m.isDictionaryPanelVisible,
                    isLoading = m.isLoading,
                    inputText = m.inputText.trim(),
                    translatedText = m.translatedText.trim(),
                    targetLang = m.targetLanguage,
                    resolvedSourceLang = m.resolvedSourceLanguage,
                    autoSource = s.workingConfiguration.dictionaryAutoSource,
                    mainVisible = isMainVisible(),
                    isQuickDictionaryVisible = m.isQuickDictionaryVisible,
                    isQuickDictionaryPinned = m.isQuickDictionaryPinned,
                    isDictionaryAutoPopupEnabled = s.workingConfiguration.isDictionaryAutoPopupEnabled,
                )
            }
            .scan(Pair<AutoLookupKey?, AutoLookupKey?>(null, null)) { (_, prev), curr -> prev to curr }
            .filter { (prev, curr) ->
                when {
                    curr == null -> false
                    // Let loading through so an in-flight translation can dismiss an unpinned stale Quick Dictionary.
                    curr.isLoading -> true
                    prev == null -> false
                    !curr.isDictionaryAutoPopupEnabled -> false
                    else -> {
                        val justFinishedLoading = prev.isLoading && !curr.isLoading
                        val settingChangedIdle = curr.translatedText.isNotBlank() && (
                            prev.autoSource != curr.autoSource ||
                                prev.isDictionaryAutoPopupEnabled != curr.isDictionaryAutoPopupEnabled ||
                                (!prev.panelVisible && curr.panelVisible)
                            )
                        justFinishedLoading || settingChangedIdle
                    }
                }
            }
            .mapNotNull { it.second }
            .collect { key ->
                if (key.isLoading) {
                    if (key.isQuickDictionaryVisible && !key.isQuickDictionaryPinned) {
                        dispatch(MainIntent.HideQuickDictionary)
                    }
                    return@collect
                }

                val preferSource = key.autoSource == DictionaryAutoSource.SOURCE
                val (word, lang) =
                    if (preferSource) key.inputText to key.resolvedSourceLang
                    else key.translatedText to key.targetLang
                val (alternate, alternateLang) =
                    if (preferSource) key.translatedText to key.targetLang
                    else key.inputText to key.resolvedSourceLang

                if (word.isBlank() || word.contains(Regex("\\s")) || word.length < 2) {
                    dispatch(MainIntent.UpdateInlineDefinition(""))
                    return@collect
                }

                dispatch(
                    MainIntent.UpdateInlineDefinition(
                        word = word,
                        language = lang,
                        alternateWord = alternate.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }.orEmpty(),
                        alternateLanguage = alternateLang
                    )
                )
                val current = mainState.value.dictionaryWord
                if (word.equals(current, ignoreCase = true)) return@collect

                // Automatic lookup only refreshes a dictionary already visible in the main window; it never opens one.
                if (key.panelVisible && key.mainVisible) {
                    withContext(Dispatchers.Swing) {
                        setDictionarySearchWord(word)
                    }
                    dispatch(MainIntent.LookupWord(word, lang))
                }
            }
    }
}

private data class AutoLookupKey(
    val panelVisible: Boolean,
    val isLoading: Boolean,
    val inputText: String,
    val translatedText: String,
    val targetLang: LanguageCode,
    val resolvedSourceLang: LanguageCode,
    val autoSource: DictionaryAutoSource,
    val mainVisible: Boolean,
    val isQuickDictionaryVisible: Boolean,
    val isQuickDictionaryPinned: Boolean,
    val isDictionaryAutoPopupEnabled: Boolean,
)

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

/**
 * Keeps the dictionary in step with the translation on screen.
 *
 * ### What switches this on
 * [com.github.ahatem.qtranslate.core.settings.data.Configuration.dictionaryAutoSource] is the only
 * master switch: `OFF` means no automatic lookup of any kind, and the other two values choose which
 * side of the translation is looked up. Nothing else may gate this. The floating Quick Dictionary
 * popup used to gate it — the checkbox labelled "show floating popup for single words" silently
 * disabled inline and docked lookup — which made the master checkbox on that page decorative and
 * turned a presentation choice into a hidden kill switch.
 *
 * ### What it produces
 * 1. A one-line inline definition for a single word, shown under the translation.
 * 2. A refresh of the dictionary dock when one is already showing the dictionary. It never opens
 *    the dock: an automatic lookup must not rearrange the window the user is working in.
 *
 * ### What it deliberately does not do
 * It never opens the floating dictionary popup. That popup opens on request (`Ctrl+D`) and nothing
 * else, which is the behaviour the 1.4.0 release notes describe: "The dictionary popup no longer
 * opens by itself whenever a single word is translated." Reviving it here would reinstate a
 * removed behaviour rather than fix a broken one.
 */
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
                    dictionaryWord = m.dictionaryWord,
                    isQuickDictionaryVisible = m.isQuickDictionaryVisible,
                    isQuickDictionaryPinned = m.isQuickDictionaryPinned,
                )
            }
            .scan(Pair<AutoLookupKey?, AutoLookupKey?>(null, null)) { (_, prev), curr -> prev to curr }
            .filter { (prev, curr) ->
                when {
                    curr == null -> false
                    // Let loading through so an in-flight translation can dismiss an unpinned stale Quick Dictionary.
                    curr.isLoading -> true
                    prev == null -> false
                    else -> {
                        val justFinishedLoading = prev.isLoading && !curr.isLoading
                        val settingChangedIdle = curr.translatedText.isNotBlank() && (
                            prev.autoSource != curr.autoSource ||
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

                // The master switch, and the only one. Turning automatic lookup off must leave no
                // definition behind from the translation before it either.
                if (key.autoSource == DictionaryAutoSource.OFF) {
                    dispatch(MainIntent.UpdateInlineDefinition(""))
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
                if (word.equals(key.dictionaryWord, ignoreCase = true)) return@collect

                // Automatic lookup only refreshes a dictionary already visible in the main window; it never opens one.
                if (key.panelVisible && key.mainVisible) {
                    withContext(Dispatchers.Swing) {
                        setDictionarySearchWord(word)
                    }
                    // The word is one side of the pair; the dictionary is pointed at the other.
                    dispatch(MainIntent.LookupWord(word, lang, alternateLang))
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
    val dictionaryWord: String,
    val isQuickDictionaryVisible: Boolean,
    val isQuickDictionaryPinned: Boolean,
)

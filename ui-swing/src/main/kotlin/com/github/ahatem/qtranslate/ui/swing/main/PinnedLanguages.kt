package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState

/**
 * The two language lists the pickers offer.
 *
 * They are not the same question, so they answer to pins differently: Pinned Languages narrows
 * what the user can translate *into*, and never what they can translate *from*.
 */
internal data class LanguageChoices(
    val sourceLanguages: List<LanguageCode>,
    val targetLanguages: List<LanguageCode>,
)

/** Both pickers' choices for [pinnedLanguages]. */
internal fun MainState.languageChoices(pinnedLanguages: List<String>): LanguageChoices =
    LanguageChoices(
        sourceLanguages = availableLanguages,
        targetLanguages = targetLanguageChoices(pinnedLanguages),
    )

/**
 * Target choices after applying [pinnedLanguages], in the active translator's own order.
 *
 * The hint on the settings page has always said "languages to show in the target language picker",
 * and this is what makes the implementation say the same thing.
 *
 * - An empty pinned list means show every language.
 * - Auto is never offered as a target; there is nothing to detect a target from.
 * - The language currently in use stays selectable even when it is not pinned, so the visible
 *   picker can never disagree with the language the translation actually used.
 * - A pin the active translator does not support contributes nothing here. The pin itself stays
 *   in the configuration, so it comes back when a translator that supports it is selected.
 */
internal fun MainState.targetLanguageChoices(pinnedLanguages: List<String>): List<LanguageCode> {
    val targets = availableLanguages.filter { it != LanguageCode.AUTO }
    if (pinnedLanguages.isEmpty()) return targets
    val pinned = pinnedLanguages.toSet()
    return targets.filter { it.tag in pinned || it == targetLanguage }
}

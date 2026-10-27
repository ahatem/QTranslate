package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState

/**
 * Narrows [MainState.availableLanguages] to the pinned languages, keeping Auto, so every
 * language picker offers the same list. An empty pinned list means show every language.
 */
internal fun MainState.withPinnedLanguages(pinned: List<String>): MainState =
    if (pinned.isEmpty()) this
    else copy(availableLanguages = availableLanguages.filter { it == LanguageCode.AUTO || it.tag in pinned })

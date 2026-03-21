package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.FontConfig

val Configuration.scaledUiFont: FontConfig
    get() = uiFontConfig.copy(
        size = (uiFontConfig.size * (uiScale / 100f)).toInt()
    )

val Configuration.scaledEditorFont: FontConfig
    get() = editorFontConfig.copy(
        size = (editorFontConfig.size * (uiScale / 100f)).toInt()
    )

val Configuration.scaledEditorFallbackFont: FontConfig
    get() = editorFallbackFontConfig.copy(
        size = (editorFallbackFontConfig.size * (uiScale / 100f)).toInt()
    )
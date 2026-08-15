package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.FontConfig

/**
 * The point size actually used for [size], given the user's zoom and the display's density.
 *
 * Two independent factors: `uiScale` is the app's own zoom setting, and [UIScale] is how many
 * device pixels the display puts behind one logical pixel. The configured size is authored against
 * a 100% display, so without the second factor the fonts stay physically small on a 150% or 200%
 * screen while every icon, inset and border around them is scaled up — text ends up looking like
 * it belongs to a different window than its own chrome.
 *
 * These sizes are installed directly as `defaultFont` and on the editors, which is why the scaling
 * has to happen here: a font handed to Swing verbatim is used verbatim.
 */
private fun FontConfig.scaledTo(uiScale: Int): FontConfig =
    copy(size = UIScale.scale((size * (uiScale / 100f)).toInt()))

val Configuration.scaledUiFont: FontConfig
    get() = uiFontConfig.scaledTo(uiScale)

val Configuration.scaledEditorFont: FontConfig
    get() = editorFontConfig.scaledTo(uiScale)

val Configuration.scaledEditorFallbackFont: FontConfig
    get() = editorFallbackFontConfig.scaledTo(uiScale)

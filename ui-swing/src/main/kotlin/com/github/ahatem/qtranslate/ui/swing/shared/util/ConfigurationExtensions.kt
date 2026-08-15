package com.github.ahatem.qtranslate.ui.swing.shared.util

import kotlin.math.roundToInt
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.FontConfig

/**
 * The point size actually used for [size], given the user's zoom.
 *
 * Only the app's own zoom is applied here, deliberately. The result is installed as `defaultFont`,
 * and FlatLaf derives its user scale factor *from* that font — so everything else in the interface
 * that goes through [UIScale] already follows the user's zoom without being told about it.
 *
 * Scaling by [UIScale] as well used to look like the thorough thing to do, and it fed the output
 * back into its own input: a larger `defaultFont` raised the scale factor, which enlarged the next
 * font, which raised the factor again. The interface grew by the zoom percentage every time the
 * font was reapplied, which is on every theme, title-bar or font change.
 *
 * The display's own density is not this function's job. On Java 9 and later the runtime scales the
 * whole window for a HiDPI display, and FlatLaf's system scale factor covers what it does not.
 */
private fun FontConfig.scaledTo(uiScale: Int): FontConfig =
    copy(size = ((size * (uiScale / 100f)).roundToInt()).coerceAtLeast(MIN_FONT_SIZE))

/** Below this a label stops being readable, and a bad zoom value should not make the app unusable. */
private const val MIN_FONT_SIZE = 6

val Configuration.scaledUiFont: FontConfig
    get() = uiFontConfig.scaledTo(uiScale)

val Configuration.scaledEditorFont: FontConfig
    get() = editorFontConfig.scaledTo(uiScale)

val Configuration.scaledEditorFallbackFont: FontConfig
    get() = editorFallbackFontConfig.scaledTo(uiScale)

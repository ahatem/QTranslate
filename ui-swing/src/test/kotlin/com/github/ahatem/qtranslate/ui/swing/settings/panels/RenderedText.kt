package com.github.ahatem.qtranslate.ui.swing.settings.panels

import java.awt.Component
import javax.swing.JLabel

/**
 * The text a Settings page shows, wherever it lives.
 *
 * Explanations and lead paragraphs are drawn by [SettingsTextBlock] rather than by a `JLabel` —
 * Swing's HTML renderer cannot break italic Arabic, which is why the block measures its own lines —
 * so an assertion about wording has to look in both kinds of component.
 */
internal fun Component.textOf(): String? = when (this) {
    is JLabel -> text
    is SettingsTextBlock -> sourceText
    else -> null
}

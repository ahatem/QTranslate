package com.github.ahatem.qtranslate.ui.swing.settings.panels

import java.awt.Component
import javax.swing.JLabel

/**
 * A label's text as the reader sees it: markup and measured line breaks removed.
 *
 * Settings explains itself through labels whose text is HTML, and since the panel computes the
 * breaks itself (Swing will not break italic Arabic) the raw `text` carries `<br>`s and escaped
 * entities that no assertion about wording should have to know about.
 */
internal fun JLabel.plainText(): String = (text ?: "")
    .replace("<br>", " ")
    .replace(Regex("<[^>]*>"), "")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")

/** The text a component shows, with markup removed, if it shows any. */
internal fun Component.textOf(): String? = (this as? JLabel)?.plainText()

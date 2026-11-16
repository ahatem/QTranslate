package com.github.ahatem.qtranslate.ui.swing.settings.panels

/**
 * A settings page that brings its own scrolling.
 *
 * Most pages are a column of rows, so the dialog wraps them in a `JScrollPane`. A page like
 * [PluginsSettingsPage] is a master–detail layout whose list and detail pane each scroll on their
 * own; wrapping it would nest scrollbars and clip the fixed action bars inside it. Implementing this
 * marker says "mount me directly".
 */
internal interface UnscrolledSettingsPage

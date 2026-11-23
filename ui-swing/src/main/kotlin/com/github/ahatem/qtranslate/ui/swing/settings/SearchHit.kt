package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel

/**
 * One row in the search results: something a query can find, and what choosing it does.
 *
 * Settings are not the only thing a query must find, though they are the rows a page registers
 * while it is being built. In a dialog whose whole point is finding things, the pages themselves
 * (typing "popups" goes to Popups) and the actions that are not settings but live on these pages
 * (opening the plugin manager, resetting a page) must be findable too. Both are modelled here so
 * that the index is an index of the dialog rather than of its form fields.
 *
 * A page hit is deliberately separate from its settings' hits: matching the page *label* against
 * every row on that page made a query like "network" answer with thirty rows, which is a worse
 * answer than the one row that says "Network — Page".
 */
internal sealed interface SearchHit {

    /** The sidebar page this hit belongs to, in the sidebar's own words. */
    val pageLabel: String

    /** A page in the sidebar. Choosing it opens the page. */
    data class Page(
        override val pageLabel: String,
        /** The sidebar group heading this page sits under, when it sits under one. */
        val groupLabel: String? = null,
    ) : SearchHit

    /** A setting on a page. Choosing it opens the page and marks the row. */
    data class Setting(
        override val pageLabel: String,
        val entry: SettingsPanel.SettingEntry,
    ) : SearchHit

    /** Something the page can *do*, such as resetting it or opening the plugin manager. */
    data class Action(
        override val pageLabel: String,
        val label: String,
        val run: () -> Unit,
    ) : SearchHit
}

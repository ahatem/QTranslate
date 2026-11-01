package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel

/**
 * One search result: a setting on a page, plus the page it lives on.
 *
 * The page label is the sidebar's own text for the page, and it is searched too — typing
 * "network" finds everything on the Network page.
 */
internal data class SearchHit(
    val pageLabel: String,
    val entry: SettingsPanel.SettingEntry,
) {
    fun matches(query: String): Boolean {
        val q = query.lowercase()
        return entry.label.lowercase().contains(q) ||
            entry.section.lowercase().contains(q) ||
            entry.hint.lowercase().contains(q) ||
            pageLabel.lowercase().contains(q)
    }
}

/**
 * Every setting on every page, indexed from panel search metadata.
 *
 * Panels are created lazily as pages are opened, so until someone searches, most do not exist
 * to be asked. Building the rest on the first search trades a pause on the first keystroke for
 * an index that cannot fall behind the panels it describes. The index is built once per dialog
 * and reused, so a page opened later by navigation is never built twice — the entries provider
 * reads the same panel cache the pages are shown from.
 *
 * @param pageLabels every selectable page, flattened, in sidebar order.
 * @param excludedPageLabels pages deliberately left unindexed. Plugins is one: it is a manager
 * for a list that changes at runtime, not a page of settings, and it is the one panel whose
 * construction does real work.
 * @param entriesForPage reads one page's searchable settings, constructing its panel on first use.
 */
internal class SettingsSearchIndex(
    private val pageLabels: List<String>,
    private val excludedPageLabels: Set<String>,
    private val entriesForPage: (String) -> List<SettingsPanel.SettingEntry>,
) {
    /** Built on the first search and reused; see class documentation. */
    private var cachedIndex: List<SearchHit>? = null

    fun search(query: String): List<SearchHit> {
        val index = cachedIndex ?: buildIndex().also { cachedIndex = it }
        return index.filter { it.matches(query) }
    }

    private fun buildIndex(): List<SearchHit> =
        pageLabels
            .filter { it !in excludedPageLabels }
            .flatMap { pageLabel ->
                entriesForPage(pageLabel).map { SearchHit(pageLabel, it) }
            }
}

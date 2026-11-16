package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel

/** One sidebar page, with the group heading it sits under when it has one. */
internal data class PageRef(val label: String, val groupLabel: String? = null)

/**
 * Whether [hit] should be listed for [query].
 *
 * Split out from the index so the matching rules are testable on their own, and kept exhaustive by
 * the sealed [SearchHit] hierarchy: a new kind of result cannot be added without deciding what a
 * query matches against in it.
 *
 * A setting is matched against its own words only — its label, its sub-section path and its
 * explanation. It used to match its page's label too, which meant "network" answered with every
 * row on the Network page; the [SearchHit.Page] result is the better answer to a page-name query,
 * and it is the reason this is no longer needed.
 */
internal fun SearchHit.matches(query: String): Boolean {
    val q = query.lowercase()
    return when (this) {
        is SearchHit.Page ->
            pageLabel.lowercase().contains(q) || groupLabel?.lowercase()?.contains(q) == true
        is SearchHit.Setting ->
            entry.label.lowercase().contains(q) ||
                entry.section.lowercase().contains(q) ||
                entry.hint.lowercase().contains(q)
        is SearchHit.Action -> label.lowercase().contains(q)
    }
}

/**
 * Everything in the dialog a query can find: pages, their settings, and their actions.
 *
 * Panels are created lazily as pages are opened, so until someone searches, most do not exist
 * to be asked. Building the rest on the first search trades a pause on the first keystroke for
 * an index that cannot fall behind the panels it describes. The index is built once per dialog
 * and reused, so a page opened later by navigation is never built twice — the entries provider
 * reads the same panel cache the pages are shown from.
 *
 * Order is meaningful: the sidebar's order, and within a page the page itself first, then its
 * settings in layout order, then its actions. A reader scanning results for a page name should not
 * have to scroll past that page's thirty rows to find it.
 *
 * @param pages every selectable page, flattened, in sidebar order.
 * @param excludedPageLabels pages deliberately left unindexed. Empty today: the former Plugins
 *   exclusion existed because that page hosted a live manager, and it is an entry-point page now,
 *   so finding it by name should work like any other page.
 * @param actionsForPage reads one page's searchable actions. Supplied by the dialog, because an
 *   action such as "Reset Page" belongs to the dialog's own behaviour rather than to the panel.
 *   Last but one so that [entriesForPage] — the argument nearly every caller passes — can stay a
 *   trailing lambda.
 * @param entriesForPage reads one page's searchable settings, constructing its panel on first use.
 */
internal class SettingsSearchIndex(
    private val pages: List<PageRef>,
    private val excludedPageLabels: Set<String> = emptySet(),
    private val actionsForPage: (String) -> List<SearchHit.Action> = { emptyList() },
    private val entriesForPage: (String) -> List<SettingsPanel.SettingEntry>,
) {
    /** Built on the first search and reused; see class documentation. */
    private var cachedIndex: List<SearchHit>? = null

    fun search(query: String): List<SearchHit> {
        val index = cachedIndex ?: buildIndex().also { cachedIndex = it }
        return index.filter { it.matches(query) }
    }

    private fun buildIndex(): List<SearchHit> =
        pages
            .filter { it.label !in excludedPageLabels }
            .flatMap { page ->
                buildList {
                    add(SearchHit.Page(page.label, page.groupLabel))
                    entriesForPage(page.label).forEach { add(SearchHit.Setting(page.label, it)) }
                    addAll(actionsForPage(page.label))
                }
            }
}

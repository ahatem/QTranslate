package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The search index is the query-independent half of settings search: what is indexed, what counts
 * as a match for each kind of result, and that the index is built once.
 */
class SettingsSearchIndexTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        return result!!
    }

    private fun entry(label: String, section: String = "Section", hint: String = "") =
        SettingsPanel.SettingEntry(label, section, hint, onEdt { JLabel() })

    @Test
    fun `nothing is built until the first search`() {
        var builtPages = 0
        val index = SettingsSearchIndex(listOf(PageRef("General"))) { builtPages++; listOf(entry("Alpha")) }

        assertEquals(0, builtPages, "the index is lazy: no panel is asked before someone searches")

        index.search("alpha")
        assertEquals(1, builtPages)
    }

    @Test
    fun `matching a setting checks its label, section and hint, case-insensitively`() {
        val entries = listOf(
            entry("Whole request timeout", "Timeouts", "A local model may think for a long time"),
        )
        val index = SettingsSearchIndex(listOf(PageRef("Network"))) { entries }

        assertTrue(index.search("timeout").any { it is SearchHit.Setting }, "label")
        assertTrue(index.search("timeouts").any { it is SearchHit.Setting }, "section")
        assertTrue(index.search("local model").any { it is SearchHit.Setting }, "hint")
        assertTrue(index.search("TIMEOUT").any { it is SearchHit.Setting }, "case-insensitive")
        assertTrue(index.search("whole request").any { it is SearchHit.Setting }, "partial label")
        assertEquals(0, index.search("zzz").size, "no field matches")
    }

    @Test
    fun `a page name is answered by the page itself, not by every row on it`() {
        val index = SettingsSearchIndex(listOf(PageRef("Network"))) {
            listOf(entry("Proxy address"), entry("Retries"))
        }

        val hits = index.search("network")

        assertEquals(1, hits.size, "one row saying where to go, rather than thirty saying nothing")
        val page = hits.single()
        assertTrue(page is SearchHit.Page, "the one result is the page")
        assertEquals("Network", page.pageLabel)
    }

    @Test
    fun `a sidebar group answers for the pages under it`() {
        val index = SettingsSearchIndex(
            listOf(
                PageRef("Window", groupLabel = "Interface"),
                PageRef("Popups", groupLabel = "Interface"),
                PageRef("Network"),
            )
        ) { listOf(entry("Some setting")) }

        val pages = index.search("interface").filterIsInstance<SearchHit.Page>()

        assertEquals(
            listOf("Window", "Popups"),
            pages.map { it.pageLabel },
            "the group heading is a way in to the pages it labels",
        )
    }

    @Test
    fun `actions are indexed and matched by their own words`() {
        val index = SettingsSearchIndex(
            pages = listOf(PageRef("Popups")),
            entriesForPage = { listOf(entry("Transparency")) },
            actionsForPage = { page ->
                listOf(SearchHit.Action(page, "Reset Page", run = {}))
            },
        )

        assertEquals(
            listOf("Reset Page"),
            index.search("reset").filterIsInstance<SearchHit.Action>().map { it.label },
        )
        assertEquals(0, index.search("popups").filterIsInstance<SearchHit.Action>().size,
            "a page-name query is answered by the page, not by every action on it")
    }

    @Test
    fun `excluded pages are never indexed`() {
        val queried = mutableListOf<String>()
        val index = SettingsSearchIndex(
            pages = listOf(PageRef("General"), PageRef("Plugins")),
            excludedPageLabels = setOf("Plugins"),
        ) { page -> queried += page; listOf(entry("$page setting")) }

        val hits = index.search("setting")

        assertEquals(1, hits.size)
        assertEquals("General", hits.single().pageLabel)
        assertEquals(listOf("General"), queried, "the excluded page is never even asked")
    }

    @Test
    fun `hits keep sidebar order, and a page comes before its own rows`() {
        val entriesByPage = mapOf(
            "Alpha" to listOf(entry("First row"), entry("Second row")),
            "Beta" to listOf(entry("Third row")),
        )
        val index = SettingsSearchIndex(listOf(PageRef("Alpha"), PageRef("Beta"))) {
            entriesByPage.getValue(it)
        }

        // An empty query matches everything, which makes the ordering observable.
        assertEquals(
            listOf("page:Alpha", "Alpha:First row", "Alpha:Second row", "page:Beta", "Beta:Third row"),
            index.search("").map { hit ->
                when (hit) {
                    is SearchHit.Page -> "page:${hit.pageLabel}"
                    is SearchHit.Setting -> "${hit.pageLabel}:${hit.entry.label}"
                    is SearchHit.Action -> "${hit.pageLabel}:${hit.label}"
                }
            },
        )
    }

    @Test
    fun `the index is built once and reused`() {
        var providerCalls = 0
        val index = SettingsSearchIndex(listOf(PageRef("Alpha"), PageRef("Beta"))) { page ->
            providerCalls++
            listOf(entry("$page setting"))
        }

        assertEquals(2, index.search("setting").size)
        assertEquals(2, index.search("setting").size)
        assertEquals(2, providerCalls, "the second search reuses the cached index")
    }
}

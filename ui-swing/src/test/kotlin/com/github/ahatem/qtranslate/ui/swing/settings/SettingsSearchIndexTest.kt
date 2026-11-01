package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The search index is the query-independent half of settings search: which pages are indexed,
 * what counts as a match, and that the index is built once.
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
        val index = SettingsSearchIndex(listOf("General"), emptySet()) { builtPages++; listOf(entry("Alpha")) }

        assertEquals(0, builtPages, "the index is lazy: no panel is asked before someone searches")

        index.search("alpha")
        assertEquals(1, builtPages)
    }

    @Test
    fun `matching checks label, section, hint and page, case-insensitively`() {
        val entries = listOf(
            entry("Whole request timeout", "Timeouts", "A local model may think for a long time"),
        )
        val index = SettingsSearchIndex(listOf("Network"), emptySet()) { entries }

        assertEquals(1, index.search("timeout").size, "label")
        assertEquals(1, index.search("timeouts").size, "section")
        assertEquals(1, index.search("local model").size, "hint")
        assertEquals(1, index.search("network").size, "page label")
        assertEquals(1, index.search("TIMEOUT").size, "case-insensitive")
        assertEquals(1, index.search("whole request").size, "partial label")
        assertEquals(0, index.search("zzz").size, "no field matches")
    }

    @Test
    fun `excluded pages are never indexed`() {
        val queried = mutableListOf<String>()
        val index = SettingsSearchIndex(
            pageLabels = listOf("General", "Plugins"),
            excludedPageLabels = setOf("Plugins"),
        ) { page -> queried += page; listOf(entry("$page setting")) }

        val hits = index.search("setting")

        assertEquals(1, hits.size)
        assertEquals("General", hits.single().pageLabel)
        assertEquals(listOf("General"), queried, "the excluded page is never even asked")
    }

    @Test
    fun `hits keep sidebar order and row order within a page`() {
        val entriesByPage = mapOf(
            "Alpha" to listOf(entry("First row"), entry("Second row")),
            "Beta" to listOf(entry("Third row")),
        )
        val index = SettingsSearchIndex(listOf("Alpha", "Beta"), emptySet()) { entriesByPage.getValue(it) }

        // An empty query matches everything, which makes the ordering observable.
        assertEquals(
            listOf("Alpha:First row", "Alpha:Second row", "Beta:Third row"),
            index.search("").map { "${it.pageLabel}:${it.entry.label}" },
        )
    }

    @Test
    fun `the index is built once and reused`() {
        var providerCalls = 0
        val index = SettingsSearchIndex(listOf("Alpha", "Beta"), emptySet()) { page ->
            providerCalls++
            listOf(entry("$page setting"))
        }

        assertEquals(2, index.search("").size)
        assertEquals(2, index.search("setting").size)
        assertEquals(2, providerCalls, "the second search reuses the cached index")
    }
}

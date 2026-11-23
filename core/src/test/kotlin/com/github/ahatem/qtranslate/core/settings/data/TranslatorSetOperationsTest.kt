package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TranslatorSetOperationsTest {

    private fun preset(primary: String?, vararg comparisons: String) = ServicePreset(
        id = "preset",
        name = "preset",
        selectedServices = mapOf(ServiceRole.TRANSLATOR to primary, ServiceRole.TTS to "tts"),
        comparisonTranslatorIds = comparisons.toList()
    )

    private fun ServicePreset.assertSet(primary: String?, vararg comparisons: String) {
        assertEquals(primary, translatorPrimaryId)
        assertEquals(comparisons.toList(), comparisonTranslatorIds)
    }

    private class Case(
        val label: String,
        val before: ServicePreset,
        val after: ServicePreset.() -> ServicePreset,
        val expectedPrimary: String?,
        val expectedComparisons: List<String>,
    )

    @Test
    fun `adding a translator appends it after the existing members`() {
        val cases = listOf(
            Case("empty set makes it Primary", preset(null), { withTranslatorAdded("a") }, "a", emptyList()),
            Case("appends after the Primary", preset("a"), { withTranslatorAdded("b") }, "a", listOf("b")),
            Case("appends after comparisons", preset("a", "b"), { withTranslatorAdded("c") }, "a", listOf("b", "c")),
            Case("legacy comparisons without a Primary are kept", preset(null, "b", "c"), { withTranslatorAdded("a") }, "a", listOf("b", "c")),
        )
        for (case in cases) {
            val result = case.after(case.before)
            assertEquals(case.expectedPrimary, result.translatorPrimaryId, case.label)
            assertEquals(case.expectedComparisons, result.comparisonTranslatorIds, case.label)
        }
        assertEquals("tts", preset("a").withTranslatorAdded("b").selectedServices[ServiceRole.TTS], "other roles untouched")
    }

    @Test
    fun `adding the Primary or an existing comparison is a no-op`() {
        val base = preset("a", "b")
        assertSame(base, base.withTranslatorAdded("a"))
        assertSame(base, base.withTranslatorAdded("b"))
    }

    // ---- remove ----

    @Test
    fun `removing a middle comparison keeps the rest in order`() {
        preset("a", "b", "c", "d").withTranslatorRemoved("c").assertSet("a", "b", "d")
    }

    @Test
    fun `removing the Primary promotes the first remaining member`() {
        preset("a", "b", "c").withTranslatorRemoved("a").assertSet("b", "c")
    }

    @Test
    fun `removing the only translator leaves no Primary and no invented replacement`() {
        val emptied = preset("a").withTranslatorRemoved("a")
        emptied.assertSet(null)
        assertNull(emptied.selectedServices[ServiceRole.TRANSLATOR])
        assertTrue(ServiceRole.TRANSLATOR in emptied.selectedServices)
    }

    @Test
    fun `removing a non-member is a no-op`() {
        val base = preset("a", "b")
        assertSame(base, base.withTranslatorRemoved("z"))
    }

    // ---- promote (existing helper, exercised through the set view) ----

    @Test
    fun `promoting a comparison puts the old Primary in its slot`() {
        preset("a", "b", "c", "d").withPromotedTranslator("c").assertSet("c", "b", "a", "d")
    }

    // ---- reorder ----

    @Test
    fun `moving swaps with the neighbouring comparison`() {
        val base = preset("a", "b", "c", "d")
        base.withTranslatorMoved("c", TranslatorMove.UP).assertSet("a", "c", "b", "d")
        base.withTranslatorMoved("b", TranslatorMove.DOWN).assertSet("a", "c", "b", "d")
    }

    @Test
    fun `moves that cannot happen are no-ops`() {
        val base = preset("a", "b", "c")
        val moves = listOf(
            "the first comparison cannot move above the Primary" to ("b" to TranslatorMove.UP),
            "the last comparison cannot move down" to ("c" to TranslatorMove.DOWN),
            "the Primary does not move" to ("a" to TranslatorMove.DOWN),
            "a non-member does not move" to ("z" to TranslatorMove.UP),
        )
        for ((label, move) in moves) {
            assertSame(base, base.withTranslatorMoved(move.first, move.second), label)
        }
    }

    // ---- unavailable ids and duplicates ----

    @Test
    fun `configured ids survive unrelated operations without any availability input`() {
        // "ghost" is not installed anywhere; membership operations never look at availability.
        val base = preset("a", "ghost", "b")
        base.withTranslatorAdded("c").assertSet("a", "ghost", "b", "c")
        base.withTranslatorRemoved("b").assertSet("a", "ghost")
        base.withTranslatorMoved("b", TranslatorMove.UP).assertSet("a", "b", "ghost")
        base.withPromotedTranslator("b").assertSet("b", "ghost", "a")
        base.withTranslatorRemoved("a").assertSet("ghost", "b")
    }

    @Test
    fun `legacy duplicates are normalized by the first operation and never reintroduced`() {
        val legacy = preset("a", "b", "a", "c", "b")
        assertEquals(listOf("a", "b", "c"), legacy.translatorSetIds)

        val results = listOf(
            legacy.withTranslatorAdded("d"),
            legacy.withTranslatorRemoved("c"),
            legacy.withTranslatorMoved("c", TranslatorMove.UP),
            legacy.withTranslatorRemoved("a")
        )
        results.forEach {
            assertEquals(it.translatorSetIds.distinct(), it.translatorSetIds)
            assertFalse(it.translatorPrimaryId in it.comparisonTranslatorIds)
        }
        legacy.withTranslatorMoved("c", TranslatorMove.UP).assertSet("a", "c", "b")
    }

    @Test
    fun `set ids list the Primary first`() {
        assertEquals(listOf("a", "b", "c"), preset("a", "b", "c").translatorSetIds)
        assertEquals(emptyList(), preset(null).translatorSetIds)
    }

    // ---- Comparison eligibility follows effective members, not configured rows ----

    @Test
    fun `working set operations feed the existing eligibility helpers`() {
        val installed = listOf("google", "bing")
        var config = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset("google")),
            activeServicePresetId = "preset"
        )
        assertEquals(1, config.effectiveTranslatorCount(installed))
        assertFalse(config.isComparisonEligible(installed))

        config = config.withTranslatorAdded("bing")
        assertEquals(2, config.effectiveTranslatorCount(installed))
        assertTrue(config.isComparisonEligible(installed))

        config = config.withTranslatorRemoved("bing")
        assertEquals(1, config.effectiveTranslatorCount(installed))
        assertFalse(config.isComparisonEligible(installed))
    }

    @Test
    fun `an unavailable member is configured but not effective`() {
        val installed = listOf("google", "bing")
        val config = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset("google", "bing", "deepl")),
            activeServicePresetId = "preset"
        )
        assertEquals(3, config.getActivePreset()!!.translatorSetIds.size)
        assertEquals(2, config.effectiveTranslatorCount(installed))
        assertTrue(config.isComparisonEligible(installed))

        val lost = config.withTranslatorRemoved("bing")
        assertEquals(1, lost.effectiveTranslatorCount(installed))
        assertFalse(lost.isComparisonEligible(installed))
    }
}

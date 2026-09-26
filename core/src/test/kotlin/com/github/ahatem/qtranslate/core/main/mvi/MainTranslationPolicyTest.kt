package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.usecase.ComparisonPolicy
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTranslationPolicyTest {
    private fun config(primary: String, comparisons: List<String> = emptyList()): Configuration =
        Configuration.DEFAULT.copy(
            servicePresets = listOf(
                ServicePreset(
                    id = "preset",
                    name = "preset",
                    selectedServices = mapOf(ServiceRole.TRANSLATOR to primary),
                    comparisonTranslatorIds = comparisons
                )
            ),
            activeServicePresetId = "preset",
            layoutPresetId = LayoutPresetIds.CLASSIC
        )

    private fun quickPolicy(cfg: Configuration, available: List<String>): ComparisonPolicy =
        quickTranslationComparisonPolicy(cfg.isComparisonEligible(available))

    @Test
    fun `only the comparison layout enables comparison work`() {
        assertEquals(ComparisonPolicy.ENABLED, mainTranslationComparisonPolicy(LayoutPresetIds.COMPARISON))
        assertEquals(ComparisonPolicy.DISABLED, mainTranslationComparisonPolicy(LayoutPresetIds.CLASSIC))
        assertEquals(ComparisonPolicy.DISABLED, mainTranslationComparisonPolicy(LayoutPresetIds.SIDE_BY_SIDE))
        assertEquals(ComparisonPolicy.DISABLED, mainTranslationComparisonPolicy(LayoutPresetIds.COMPACT))
    }

    @Test
    fun `quick with one effective translator is canonical only`() {
        assertEquals(ComparisonPolicy.DISABLED, quickPolicy(config("google"), listOf("google")))
        // A configured but unavailable comparison id does not make Quick compare.
        assertEquals(
            ComparisonPolicy.DISABLED,
            quickPolicy(config("google", listOf("ghost")), listOf("google"))
        )
    }

    @Test
    fun `quick with two effective translators compares`() {
        assertEquals(
            ComparisonPolicy.ENABLED,
            quickPolicy(config("google", listOf("bing")), listOf("google", "bing"))
        )
    }

    @Test
    fun `quick counts the resolved fallback primary`() {
        // Saved primary is gone but google resolves: google + deepl is a real pair.
        assertEquals(
            ComparisonPolicy.ENABLED,
            quickPolicy(config("ghost", listOf("deepl")), listOf("google", "deepl"))
        )
    }

    @Test
    fun `fallback notice fires once per entry and re-arms after recovery`() {
        assertTrue(shouldAnnounceComparisonFallback(needsFallback = true, alreadyAnnounced = false))
        assertFalse(shouldAnnounceComparisonFallback(needsFallback = true, alreadyAnnounced = true))
        assertFalse(shouldAnnounceComparisonFallback(needsFallback = false, alreadyAnnounced = false))
        assertFalse(shouldAnnounceComparisonFallback(needsFallback = false, alreadyAnnounced = true))
    }
}

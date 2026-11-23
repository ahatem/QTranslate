package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.workspace.translation.ComparisonPolicy
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import kotlin.test.Test
import kotlin.test.assertEquals

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
    }

    @Test
    fun `quick compares only when two translators are effective`() {
        class Case(val label: String, val cfg: Configuration, val available: List<String>, val expected: ComparisonPolicy)

        val cases = listOf(
            Case("one translator", config("google"), listOf("google"), ComparisonPolicy.DISABLED),
            Case("unavailable comparison id", config("google", listOf("ghost")), listOf("google"), ComparisonPolicy.DISABLED),
            Case("two translators", config("google", listOf("bing")), listOf("google", "bing"), ComparisonPolicy.ENABLED),
            // The saved primary is gone but google resolves, so google + deepl is a real pair.
            Case("resolved fallback primary counts", config("ghost", listOf("deepl")), listOf("google", "deepl"), ComparisonPolicy.ENABLED),
        )

        for (case in cases) {
            assertEquals(case.expected, quickPolicy(case.cfg, case.available), case.label)
        }
    }
}

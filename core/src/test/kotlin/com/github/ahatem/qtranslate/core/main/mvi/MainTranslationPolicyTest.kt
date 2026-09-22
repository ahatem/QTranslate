package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.main.domain.usecase.ComparisonPolicy
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTranslationPolicyTest {
    @Test
    fun `only the comparison layout enables comparison work`() {
        assertEquals(ComparisonPolicy.ENABLED, mainTranslationComparisonPolicy(LayoutPresetIds.COMPARISON))
        assertEquals(ComparisonPolicy.DISABLED, mainTranslationComparisonPolicy(LayoutPresetIds.CLASSIC))
        assertEquals(ComparisonPolicy.DISABLED, mainTranslationComparisonPolicy(LayoutPresetIds.SIDE_BY_SIDE))
        assertEquals(ComparisonPolicy.DISABLED, mainTranslationComparisonPolicy(LayoutPresetIds.COMPACT))
    }
}

package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutTypeTest {
    @Test
    fun `comparison is a persisted first class layout after the existing presets`() {
        assertEquals(LayoutPresetIds.COMPARISON, LayoutType.COMPARISON.id)
        assertEquals(
            listOf(LayoutType.CLASSIC, LayoutType.SIDE_BY_SIDE, LayoutType.COMPACT, LayoutType.COMPARISON),
            LayoutManager.getAvailableLayouts().map { it.type }
        )
    }

    @Test
    fun `every layout has a localized label`() {
        assertTrue(LayoutManager.getAvailableLayouts().all { it.type.localizeId.startsWith("layout_preset_") })
    }
}

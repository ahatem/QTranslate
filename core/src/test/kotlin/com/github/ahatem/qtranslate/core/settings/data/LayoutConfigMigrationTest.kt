package com.github.ahatem.qtranslate.core.settings.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v10 → v11 mapping, proved over the whole space it will ever be run against.
 *
 * The point of an exhaustive test here is that this is the one place a stale setting can turn into
 * a window the user cannot use — a hidden history row in a layout that has no other history
 * controls, a selector that was hidden and comes back, a comparison board chosen by someone with a
 * single translator. The space is small enough to enumerate, so nothing about this mapping has to
 * be reasoned about from examples.
 */
class LayoutConfigMigrationTest {

    /** One stored v10 state: everything about the window the old model could hold. */
    private data class StoredState(
        val layoutPresetId: String,
        val visibility: ToolbarVisibility,
        val selectorStyle: ServiceSelectorStyle,
        val appearance: ServiceSelectorAppearance,
    ) {
        fun migrate(): LayoutConfig = Configuration.DEFAULT.copy(
            layoutPresetId = layoutPresetId,
            toolbarVisibility = visibility,
            serviceSelectorStyle = selectorStyle,
            serviceSelectorAppearance = appearance,
        ).layoutConfig()
    }

    /** Every layout id a stored configuration could hold, including retired and unrecognised ones. */
    private val storedLayoutIds = listOf(
        LayoutPresetIds.CLASSIC,
        LayoutPresetIds.SIDE_BY_SIDE,
        LayoutPresetIds.COMPARISON,
        LayoutPresetIds.LEGACY_COMPACT,
        "COMPARISON",
        "",
        "no-such-layout"
    )

    /** All sixteen combinations of the four chrome switches. */
    private fun everyVisibility(): List<ToolbarVisibility> = (0 until 16).map { bits ->
        ToolbarVisibility(
            isHistoryBarVisible = bits and 1 == 0,
            isLanguageBarVisible = bits and 2 == 0,
            isServicesPanelVisible = bits and 4 == 0,
            isStatusBarVisible = bits and 8 == 0
        )
    }

    /** 3 offered ids x 16 chrome combinations x 2 selector styles x 3 selector appearances. */
    private fun everyStoredState(): List<StoredState> =
        listOf(LayoutPresetIds.CLASSIC, LayoutPresetIds.SIDE_BY_SIDE, LayoutPresetIds.COMPARISON)
            .flatMap { id ->
                everyVisibility().flatMap { visibility ->
                    ServiceSelectorStyle.values().flatMap { style ->
                        ServiceSelectorAppearance.values().map { appearance ->
                            StoredState(id, visibility, style, appearance)
                        }
                    }
                }
            }

    private fun migrate(
        id: String,
        visibility: ToolbarVisibility,
        style: ServiceSelectorStyle = ServiceSelectorStyle.ENHANCED
    ): LayoutConfig = LayoutConfigMigration.fromV10(id, visibility, style)

    private fun LayoutGeometry.toConfig(): LayoutConfig =
        LayoutConfig(paneLayout, toolbarStyle, serviceSelector ?: ServiceSelector.PICKER, showStatusBar)

    @Test
    fun `every stored state migrates, and does so deterministically`() {
        val states = everyStoredState()
        assertEquals(288, states.size, "the old space is 3 offered ids x 16 x 2 styles x 3 appearances")
        states.forEach { state ->
            assertEquals(state.migrate(), state.migrate(), "$state must map the same way every time")
        }
    }

    @Test
    fun `the classic selector appearance is dropped rather than carried over`() {
        // It only ever changed the bottom strip's labels, so it has no successor here and must not
        // leak into a field that means something else.
        val byEverythingElse = everyStoredState()
            .groupBy { Triple(it.layoutPresetId, it.visibility, it.selectorStyle) }
        assertEquals(288 / 3, byEverythingElse.size)
        byEverythingElse.forEach { (input, states) ->
            assertEquals(
                1,
                states.map { it.migrate() }.distinct().size,
                "appearance must not change the mapping of $input"
            )
        }
    }

    @Test
    fun `an installation nobody has configured lands on the classic card`() {
        val migrated = LayoutConfigMigration.fromV10(Configuration.DEFAULT)
        assertEquals(LayoutConfig(PaneLayout.STACKED, ToolbarStyle.FULL, ServiceSelector.PICKER, true), migrated)
        assertEquals(LayoutPreset.CLASSIC, migrated.matchingPreset())
        assertEquals(LayoutPreset.CLASSIC, LayoutConfigMigration.fromV10(Configuration.FRESH_INSTALL).matchingPreset())
    }

    @Test
    fun `each legacy layout keeps its own card highlighted after upgrading`() {
        val untouched = ToolbarVisibility.DEFAULT
        assertEquals(LayoutPreset.CLASSIC, migrate(LayoutPresetIds.CLASSIC, untouched).matchingPreset())
        assertEquals(LayoutPreset.SIDE_BY_SIDE, migrate(LayoutPresetIds.SIDE_BY_SIDE, untouched).matchingPreset())
        assertEquals(LayoutPreset.COMPARISON, migrate(LayoutPresetIds.COMPARISON, untouched).matchingPreset())
        // The retired id resolves to the layout it always meant.
        assertEquals(PaneLayout.STACKED, migrate(LayoutPresetIds.LEGACY_COMPACT, untouched).paneLayout)
    }

    @Test
    fun `hiding the language bar asks for the slimmest legal row, not two rows`() {
        val hidLanguageBar = ToolbarVisibility.DEFAULT.copy(isLanguageBarVisible = false)
        assertEquals(ToolbarStyle.COMPACT, migrate(LayoutPresetIds.CLASSIC, hidLanguageBar).toolbarStyle)
        assertEquals(LayoutPreset.COMPACT, migrate(LayoutPresetIds.CLASSIC, hidLanguageBar).matchingPreset())
    }

    @Test
    fun `hiding the history row outranks every other chrome switch`() {
        // Otherwise someone who hid history would be handed a style that draws it again.
        everyVisibility().filter { !it.isHistoryBarVisible }.forEach { visibility ->
            assertEquals(ToolbarStyle.MINIMAL, migrate(LayoutPresetIds.CLASSIC, visibility).toolbarStyle)
        }
    }

    @Test
    fun `the bottom strip survives only where it can actually be shown`() {
        val shown = ToolbarVisibility.DEFAULT
        val hidden = ToolbarVisibility.DEFAULT.copy(isServicesPanelVisible = false)
        assertEquals(
            ServiceSelector.BOTTOM_STRIP,
            migrate(LayoutPresetIds.CLASSIC, shown, ServiceSelectorStyle.CLASSIC).serviceSelector
        )
        // A classic selector with its panel hidden drew nothing at all; the picker is the only
        // arrangement in which the active service is visible somewhere.
        assertEquals(
            ServiceSelector.PICKER,
            migrate(LayoutPresetIds.CLASSIC, hidden, ServiceSelectorStyle.CLASSIC).serviceSelector
        )
        assertEquals(
            ServiceSelector.PICKER,
            migrate(LayoutPresetIds.CLASSIC, shown, ServiceSelectorStyle.ENHANCED).serviceSelector
        )
    }

    @Test
    fun `upgrading can only ever highlight one of the four cards`() {
        assertEquals(
            LayoutPreset.entries.toSet(),
            everyStoredState().map { it.migrate().matchingPreset() }.filterNotNull().toSet(),
            "every card must be reachable by some upgraded installation, and no other card exists"
        )
    }

    @Test
    fun `four of the fifteen arrangements are a card, which is what exact matching means`() {
        // Worth pinning, because it is a design decision rather than an accident: a card highlights
        // only while the four values equal it exactly, so 11 of the 15 arrangements — side by side
        // with a compact toolbar, comparison with a compact toolbar, the legacy bottom strip, and
        // everything with the footer hidden — highlight nothing.
        val withFooter = LayoutGeometry.all().filter { it.showStatusBar }
        assertEquals(15, withFooter.size)
        val carded = withFooter.filter { it.toConfig().matchingPreset() != null }
        assertEquals(
            setOf(
                LayoutGeometry(PaneLayout.STACKED, ToolbarStyle.FULL, ServiceSelector.PICKER, true),
                LayoutGeometry(PaneLayout.STACKED, ToolbarStyle.COMPACT, ServiceSelector.PICKER, true),
                LayoutGeometry(PaneLayout.SIDE_BY_SIDE, ToolbarStyle.FULL, ServiceSelector.PICKER, true),
                LayoutGeometry(PaneLayout.COMPARISON, ToolbarStyle.FULL, null, true),
            ),
            carded.toSet()
        )
        // Nothing with the footer hidden is a card, since all four cards show it.
        assertTrue(LayoutGeometry.all().filter { !it.showStatusBar }.all { it.toConfig().matchingPreset() == null })
    }

    @Test
    fun `the comparison card is matched whatever the selector says`() {
        val withStrip = LayoutConfig(PaneLayout.COMPARISON, ToolbarStyle.FULL, ServiceSelector.BOTTOM_STRIP, true)
        val withPicker = withStrip.copy(serviceSelector = ServiceSelector.PICKER)
        assertEquals(LayoutPreset.COMPARISON, withStrip.matchingPreset())
        assertEquals(LayoutPreset.COMPARISON, withPicker.matchingPreset())
        // The stored choice is remembered even though it cannot be shown.
        assertEquals(ServiceSelector.BOTTOM_STRIP, withStrip.serviceSelector)
        assertNull(withStrip.geometry.serviceSelector)
    }

    @Test
    fun `fifteen geometries without the footer and thirty with it`() {
        val geometries = LayoutGeometry.all()
        assertEquals(30, geometries.size)
        assertEquals(30, geometries.distinct().size, "no two configurations may be the same picture")
        assertEquals(15, geometries.count { it.showStatusBar })
        assertEquals(15, geometries.count { !it.showStatusBar })
        assertEquals(
            ToolbarStyle.entries.toSet(),
            geometries.filter { it.paneLayout == PaneLayout.COMPARISON }.map { it.toolbarStyle }.toSet()
        )
        assertTrue(geometries.filter { it.paneLayout == PaneLayout.COMPARISON }.all { it.serviceSelector == null })
        assertTrue(geometries.filter { it.paneLayout != PaneLayout.COMPARISON }.all { it.serviceSelector != null })
    }

    @Test
    fun `the runtime arrangement matches the rule it replaces, for every layout and both eligibilities`() {
        fun legacy(id: String, eligible: Boolean): String {
            val saved = LayoutPresetIds.resolve(id)
            return if (saved == LayoutPresetIds.COMPARISON && !eligible) LayoutPresetIds.CLASSIC else saved
        }

        listOf(true, false).forEach { eligible ->
            storedLayoutIds.forEach { id ->
                val fromModel = PaneLayout.fromPresetId(id)
                    .let { if (it == PaneLayout.COMPARISON && !eligible) PaneLayout.STACKED else it }
                    .presetId
                assertEquals(
                    legacy(id, eligible),
                    fromModel,
                    "$id must arrange the same way through the model (eligible=$eligible)"
                )
            }
        }
    }

    @Test
    fun `an ineligible comparison falls back without forgetting the preference`() {
        val requested = LayoutConfig(PaneLayout.COMPARISON, ToolbarStyle.FULL, ServiceSelector.PICKER, true)
        assertEquals(PaneLayout.STACKED, requested.effectivePaneLayout(isComparisonEligible = false))
        assertEquals(PaneLayout.COMPARISON, requested.paneLayout, "the request itself is never rewritten")
        assertEquals(PaneLayout.COMPARISON, requested.effectivePaneLayout(isComparisonEligible = true))
        // It keeps its own card highlighted while standing down, so the page does not lose its place.
        assertEquals(LayoutPreset.COMPARISON, requested.matchingPreset())
    }

    @Test
    fun `a card is never ambiguous`() {
        // Two cards that differ in nothing structural would make the highlight arbitrary.
        val geometries = LayoutPreset.entries.map { it.config.geometry }
        assertEquals(LayoutPreset.entries.size, geometries.distinct().size)
        LayoutPreset.entries.forEach { preset ->
            assertEquals(preset, preset.config.matchingPreset(), "${preset.name} must highlight itself")
        }
    }
}

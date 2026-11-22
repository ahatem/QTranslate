package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The slot model: total migration, one row plan, reachable features, and partial starting points. */
class WindowLayoutTest {

    // ---- Enumerations of the spaces under test ----

    private val storedLayoutIds = listOf(
        LayoutPresetIds.CLASSIC,
        LayoutPresetIds.SIDE_BY_SIDE,
        LayoutPresetIds.COMPARISON,
        LayoutPresetIds.LEGACY_COMPACT,
    )

    private fun everyVisibility(): List<ToolbarVisibility> = (0 until 16).map { bits ->
        ToolbarVisibility(
            isHistoryBarVisible = bits and 1 == 0,
            isLanguageBarVisible = bits and 2 == 0,
            isServicesPanelVisible = bits and 4 == 0,
            isStatusBarVisible = bits and 8 == 0,
        )
    }

    private data class V10(
        val id: String,
        val visibility: ToolbarVisibility,
        val style: ServiceSelectorStyle,
        val appearance: ServiceSelectorAppearance,
    ) {
        fun migrate(): WindowLayout = WindowLayoutMigration.fromV10(id, visibility, style, appearance)
    }

    /** 4 ids (legacy compact included) x 16 visibility combos x 2 styles x 3 appearances. */
    private fun everyV10(): List<V10> = storedLayoutIds.flatMap { id ->
        everyVisibility().flatMap { visibility ->
            ServiceSelectorStyle.entries.flatMap { style ->
                ServiceSelectorAppearance.entries.map { V10(id, visibility, style, appearance = it) }
            }
        }
    }

    /** Every value the new model can hold: 3 x 4^3 x 2 x 3 x 2 x 2 x 2. */
    private fun everyLayout(): List<WindowLayout> =
        PaneLayout.entries.flatMap { panes ->
            RegionSlot.entries.flatMap { history ->
                RegionSlot.entries.flatMap { languages ->
                    RegionSlot.entries.flatMap { services ->
                        ServiceWidget.entries.flatMap { widget ->
                            ServiceSelectorAppearance.entries.flatMap { appearance ->
                                listOf(false, true).flatMap { share ->
                                    listOf(false, true).flatMap { status ->
                                        Density.entries.map { density ->
                                            WindowLayout(
                                                panes, history, languages, services, widget,
                                                appearance, share, status, density,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    /** The pane arrangements a layout can actually be built with: Comparison may fall back to Stacked. */
    private fun WindowLayout.effectiveVariants(): List<PaneLayout> =
        if (panes == PaneLayout.COMPARISON) listOf(PaneLayout.COMPARISON, PaneLayout.STACKED) else listOf(panes)

    // ---- Defaults ----

    @Test
    fun `default is the modern classic window`() {
        val d = WindowLayout.DEFAULT
        assertEquals(PaneLayout.STACKED, d.panes)
        assertEquals(RegionSlot.TOP, d.history)
        assertEquals(RegionSlot.ABOVE_RESULTS, d.languages)
        assertEquals(RegionSlot.BOTTOM, d.services)
        assertEquals(ServiceWidget.PICKER, d.serviceWidget)
        assertEquals(ServiceSelectorAppearance.ICONS_AND_TEXT, d.stripAppearance)
        assertFalse(d.shareRows)
        assertTrue(d.showStatusBar)
        assertEquals(Density.COMFORTABLE, d.density)
    }

    @Test
    fun `layouts and profiles survive a JSON round trip`() {
        val json = Json
        val layout = WindowPreset.FOCUS.applyTo(WindowLayout.DEFAULT.copy(panes = PaneLayout.SIDE_BY_SIDE))
        assertEquals(layout, json.decodeFromString<WindowLayout>(json.encodeToString(WindowLayout.serializer(), layout)))
        val profile = LayoutProfile("Writing", layout)
        assertEquals(profile, json.decodeFromString<LayoutProfile>(json.encodeToString(LayoutProfile.serializer(), profile)))
        assertEquals(20, LayoutProfile.MAX_PROFILES)
    }

    // ---- Migration ----

    @Test
    fun `the v10 space is 4 ids x 16 x 2 x 3`() {
        assertEquals(384, everyV10().size)
    }

    @Test
    fun `every v10 state migrates deterministically and places regions per pane`() {
        everyV10().forEach { v ->
            val migrated = v.migrate()
            assertEquals(migrated, v.migrate(), "deterministic for $v")

            val panes = PaneLayout.fromPresetId(v.id)
            assertEquals(panes, migrated.panes, "panes for $v")

            assertEquals(
                if (v.visibility.isHistoryBarVisible) RegionSlot.TOP else RegionSlot.HIDDEN,
                migrated.history, "history for $v",
            )
            val expectedLanguages = when {
                !v.visibility.isLanguageBarVisible -> RegionSlot.HIDDEN
                panes == PaneLayout.SIDE_BY_SIDE -> RegionSlot.TOP
                else -> RegionSlot.ABOVE_RESULTS
            }
            assertEquals(expectedLanguages, migrated.languages, "languages for $v")
            assertEquals(
                if (v.visibility.isServicesPanelVisible) RegionSlot.BOTTOM else RegionSlot.HIDDEN,
                migrated.services, "services for $v",
            )
            assertEquals(
                if (v.style == ServiceSelectorStyle.CLASSIC) ServiceWidget.STRIP else ServiceWidget.PICKER,
                migrated.serviceWidget, "widget for $v",
            )
            assertEquals(v.appearance, migrated.stripAppearance, "appearance for $v")
            assertFalse(migrated.shareRows, "existing users keep one row per region")
            assertEquals(v.visibility.isStatusBarVisible, migrated.showStatusBar)
            assertEquals(Density.COMFORTABLE, migrated.density)
        }
    }

    @Test
    fun `legacy compact and unknown ids migrate as stacked panes`() {
        val stacked = WindowLayoutMigration.fromV10(
            LayoutPresetIds.CLASSIC, ToolbarVisibility.DEFAULT,
            ServiceSelectorStyle.ENHANCED, ServiceSelectorAppearance.ICONS_AND_TEXT,
        )
        listOf(LayoutPresetIds.LEGACY_COMPACT, "no-such-layout", "").forEach { id ->
            assertEquals(
                stacked,
                WindowLayoutMigration.fromV10(
                    id, ToolbarVisibility.DEFAULT,
                    ServiceSelectorStyle.ENHANCED, ServiceSelectorAppearance.ICONS_AND_TEXT,
                ),
                "id '$id'",
            )
        }
    }

    @Test
    fun `the language row keeps its v10 position in each pane layout`() {
        fun languagesFor(id: String) = WindowLayoutMigration.fromV10(
            id, ToolbarVisibility.DEFAULT, ServiceSelectorStyle.ENHANCED, ServiceSelectorAppearance.ICONS_AND_TEXT,
        ).languages
        assertEquals(RegionSlot.ABOVE_RESULTS, languagesFor(LayoutPresetIds.CLASSIC))
        assertEquals(RegionSlot.TOP, languagesFor(LayoutPresetIds.SIDE_BY_SIDE))
        assertEquals(RegionSlot.ABOVE_RESULTS, languagesFor(LayoutPresetIds.COMPARISON))
    }

    @Test
    fun `a default v10 configuration migrates to the default layout`() {
        assertEquals(WindowLayout.DEFAULT, WindowLayoutMigration.fromV10(Configuration.DEFAULT))
    }

    @Test
    fun `the configuration overload reads the four v10 fields`() {
        val config = Configuration.DEFAULT.copy(
            layoutPresetId = LayoutPresetIds.SIDE_BY_SIDE,
            toolbarVisibility = ToolbarVisibility(isHistoryBarVisible = false, isStatusBarVisible = false),
            serviceSelectorStyle = ServiceSelectorStyle.CLASSIC,
            serviceSelectorAppearance = ServiceSelectorAppearance.TEXT_ONLY,
        )
        assertEquals(
            WindowLayout(
                panes = PaneLayout.SIDE_BY_SIDE,
                history = RegionSlot.HIDDEN,
                languages = RegionSlot.TOP,
                services = RegionSlot.BOTTOM,
                serviceWidget = ServiceWidget.STRIP,
                stripAppearance = ServiceSelectorAppearance.TEXT_ONLY,
                showStatusBar = false,
            ),
            WindowLayoutMigration.fromV10(config),
        )
    }

    // ---- Starting points ----

    @Test
    fun `modern classic applied to anything is the default except the strip appearance`() {
        everyLayout().forEach { base ->
            val applied = WindowPreset.MODERN_CLASSIC.recipe.applyTo(base)
            assertEquals(WindowLayout.DEFAULT.copy(stripAppearance = base.stripAppearance), applied)
        }
        assertEquals(WindowLayout.DEFAULT, WindowPreset.MODERN_CLASSIC.applyTo(WindowLayout.DEFAULT))
    }

    @Test
    fun `each card changes only the values it names`() {
        val base = WindowLayout(
            panes = PaneLayout.COMPARISON,
            history = RegionSlot.BOTTOM,
            languages = RegionSlot.HIDDEN,
            services = RegionSlot.TOP,
            serviceWidget = ServiceWidget.STRIP,
            stripAppearance = ServiceSelectorAppearance.TEXT_ONLY,
            shareRows = false,
            showStatusBar = true,
            density = Density.COMFORTABLE,
        )
        // Written from the plan's card table, independently of the recipes.
        val expected = mapOf(
            WindowPreset.MODERN_CLASSIC to base.copy(
                panes = PaneLayout.STACKED, history = RegionSlot.TOP, languages = RegionSlot.ABOVE_RESULTS,
                services = RegionSlot.BOTTOM, serviceWidget = ServiceWidget.PICKER,
            ),
            WindowPreset.CLASSIC to base.copy(
                panes = PaneLayout.STACKED, history = RegionSlot.TOP, languages = RegionSlot.ABOVE_RESULTS,
                services = RegionSlot.BOTTOM, serviceWidget = ServiceWidget.STRIP,
                stripAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT, density = Density.COMPACT,
            ),
            WindowPreset.COMPACT to base.copy(
                history = RegionSlot.TOP, languages = RegionSlot.TOP, shareRows = true,
            ),
            WindowPreset.FOCUS to base.copy(
                history = RegionSlot.TOP, languages = RegionSlot.TOP, services = RegionSlot.HIDDEN,
                shareRows = true, showStatusBar = false, density = Density.COMPACT,
            ),
        )
        assertEquals(WindowPreset.entries.toSet(), expected.keys)
        expected.forEach { (preset, layout) -> assertEquals(layout, preset.applyTo(base), "$preset") }
    }

    @Test
    fun `compact and focus leave the panes alone`() {
        PaneLayout.entries.forEach { panes ->
            val base = WindowLayout.DEFAULT.copy(panes = panes)
            assertEquals(panes, WindowPreset.COMPACT.applyTo(base).panes)
            assertEquals(panes, WindowPreset.FOCUS.applyTo(base).panes)
        }
    }

    @Test
    fun `compact leaves services status bar and density alone`() {
        everyLayout().forEach { base ->
            val applied = WindowPreset.COMPACT.applyTo(base)
            assertEquals(base.services, applied.services)
            assertEquals(base.serviceWidget, applied.serviceWidget)
            assertEquals(base.showStatusBar, applied.showStatusBar)
            assertEquals(base.density, applied.density)
        }
    }

    @Test
    fun `an empty recipe changes nothing`() {
        everyLayout().forEach { assertEquals(it, WindowRecipe().applyTo(it)) }
    }

    // ---- Row plan ----

    private val visibleSlots = listOf(RegionSlot.TOP, RegionSlot.ABOVE_RESULTS, RegionSlot.BOTTOM)

    @Test
    fun `row plan lists exactly the visible slots in order`() {
        val plan = WindowLayout.DEFAULT.rowPlan(PaneLayout.STACKED)
        assertEquals(visibleSlots, plan.keys.toList())
        assertEquals(
            mapOf(
                RegionSlot.TOP to listOf(listOf(WindowRegion.HISTORY)),
                RegionSlot.ABOVE_RESULTS to listOf(listOf(WindowRegion.LANGUAGES)),
                RegionSlot.BOTTOM to listOf(listOf(WindowRegion.SERVICES)),
            ),
            plan,
        )
    }

    @Test
    fun `row plan over the whole space keeps its invariants`() {
        everyLayout().forEach { layout ->
            layout.effectiveVariants().forEach { effective ->
                val plan = layout.rowPlan(effective)
                assertEquals(visibleSlots, plan.keys.toList(), "$layout / $effective")

                val all = plan.values.flatten().flatten()
                assertEquals(all.toSet().size, all.size, "no region appears twice: $layout / $effective")

                WindowRegion.entries.forEach { region ->
                    val expectedPresent = layout.slotOf(region) != RegionSlot.HIDDEN &&
                        !(region == WindowRegion.SERVICES && effective == PaneLayout.COMPARISON)
                    assertEquals(expectedPresent, region in all, "$region presence: $layout / $effective")
                }

                plan.forEach { (slot, rows) ->
                    rows.forEach { row ->
                        assertTrue(row.isNotEmpty(), "no empty row")
                        assertTrue(row.all { layout.slotOf(it) == slot }, "regions sit in their own slot")
                        assertEquals(row.sortedBy { it.ordinal }, row, "row order: $layout")
                        if (WindowRegion.SERVICES in row && layout.serviceWidget == ServiceWidget.STRIP) {
                            assertEquals(listOf(WindowRegion.SERVICES), row, "the strip is alone: $layout")
                        }
                    }
                    // Rows themselves follow history, languages, services.
                    val firsts = rows.map { it.first().ordinal }
                    assertEquals(firsts.sorted(), firsts, "row order within slot: $layout")
                    if (!layout.shareRows) {
                        assertTrue(rows.all { it.size == 1 }, "stacked rows hold one region: $layout")
                    }
                }
            }
        }
    }

    @Test
    fun `shared rows merge a slot unless the strip is in it`() {
        val shared = WindowLayout(
            history = RegionSlot.TOP, languages = RegionSlot.TOP, services = RegionSlot.TOP, shareRows = true,
        )
        assertEquals(
            listOf(listOf(WindowRegion.HISTORY, WindowRegion.LANGUAGES, WindowRegion.SERVICES)),
            shared.rowPlan(PaneLayout.STACKED)[RegionSlot.TOP],
        )
        assertEquals(
            listOf(
                listOf(WindowRegion.HISTORY, WindowRegion.LANGUAGES),
                listOf(WindowRegion.SERVICES),
            ),
            shared.copy(serviceWidget = ServiceWidget.STRIP).rowPlan(PaneLayout.STACKED)[RegionSlot.TOP],
        )
        assertEquals(
            listOf(listOf(WindowRegion.HISTORY), listOf(WindowRegion.LANGUAGES), listOf(WindowRegion.SERVICES)),
            shared.copy(shareRows = false).rowPlan(PaneLayout.STACKED)[RegionSlot.TOP],
        )
    }

    @Test
    fun `comparison drops the services region but keeps its other rows`() {
        val plan = WindowLayout.DEFAULT.copy(panes = PaneLayout.COMPARISON).rowPlan(PaneLayout.COMPARISON)
        assertEquals(emptyList(), plan.getValue(RegionSlot.BOTTOM))
        assertEquals(listOf(listOf(WindowRegion.HISTORY)), plan.getValue(RegionSlot.TOP))
        assertEquals(listOf(listOf(WindowRegion.LANGUAGES)), plan.getValue(RegionSlot.ABOVE_RESULTS))
    }

    @Test
    fun `a comparison that falls back to stacked keeps its services region`() {
        val layout = WindowLayout.DEFAULT.copy(panes = PaneLayout.COMPARISON)
        assertEquals(listOf(listOf(WindowRegion.SERVICES)), layout.rowPlan(PaneLayout.STACKED).getValue(RegionSlot.BOTTOM))
        assertEquals(PaneLayout.STACKED, layout.effectivePanes(isComparisonEligible = false))
        assertEquals(PaneLayout.COMPARISON, layout.effectivePanes(isComparisonEligible = true))
        assertEquals(PaneLayout.SIDE_BY_SIDE, layout.copy(panes = PaneLayout.SIDE_BY_SIDE).effectivePanes(false))
    }

    // ---- Routes, cross-checked against the row plan ----

    private val regionOf = mapOf(
        WindowFeature.BACK_FORWARD to WindowRegion.HISTORY,
        WindowFeature.IMAGE_AND_DOCUMENT to WindowRegion.HISTORY,
        WindowFeature.LANGUAGE_PAIR to WindowRegion.LANGUAGES,
        WindowFeature.TRANSLATE_ACTION to WindowRegion.LANGUAGES,
        WindowFeature.SERVICE_SWITCH to WindowRegion.SERVICES,
    )

    @Test
    fun `every feature has a host that the row plan agrees with`() {
        everyLayout().forEach { layout ->
            layout.effectiveVariants().forEach { effective ->
                val hosts = layout.requiredHosts(effective)
                val plan = layout.rowPlan(effective)
                val shown = plan.values.flatten().flatten().toSet()
                val context = "$layout / $effective"

                assertEquals(WindowFeature.entries.toSet(), hosts.keys, "every feature has a host: $context")

                hosts.forEach { (feature, host) ->
                    when (host) {
                        is FeatureHost.RegionRow -> {
                            assertTrue(host.region in shown, "$feature -> ${host.region} must be drawn: $context")
                            assertEquals(regionOf[feature], host.region, "$feature belongs to its own region: $context")
                        }
                        FeatureHost.InputStrip -> if (feature == WindowFeature.NOTIFICATIONS) {
                            assertFalse(layout.showStatusBar, context)
                            assertTrue(WindowRegion.HISTORY !in shown, "no history row to host it: $context")
                        } else {
                            val region = regionOf.getValue(feature)
                            assertTrue(region !in shown, "$feature falls back only if $region is not drawn: $context")
                            assertFalse(
                                feature == WindowFeature.SERVICE_SWITCH && effective == PaneLayout.COMPARISON,
                                "the comparison header owns switching: $context",
                            )
                        }
                        FeatureHost.ComparisonHeader -> {
                            assertEquals(WindowFeature.SERVICE_SWITCH, feature, context)
                            assertEquals(PaneLayout.COMPARISON, effective, context)
                            assertTrue(WindowRegion.SERVICES !in shown, context)
                        }
                        FeatureHost.StatusBar -> {
                            assertEquals(WindowFeature.NOTIFICATIONS, feature, context)
                            assertTrue(layout.showStatusBar, context)
                        }
                        FeatureHost.HistoryRowEnd -> {
                            assertEquals(WindowFeature.NOTIFICATIONS, feature, context)
                            assertFalse(layout.showStatusBar, context)
                            assertTrue(WindowRegion.HISTORY in shown, "history row must exist: $context")
                        }
                    }
                }

                // The converse: a drawn region hosts its own features, so nothing is double-homed.
                regionOf.forEach { (feature, region) ->
                    val host = hosts.getValue(feature)
                    if (region in shown) {
                        assertEquals(FeatureHost.RegionRow(region), host, "$feature stays in its drawn row: $context")
                    } else {
                        assertTrue(host !is FeatureHost.RegionRow, "$feature cannot sit in an absent row: $context")
                    }
                }
            }
        }
    }

    @Test
    fun `hosts follow the fallback table`() {
        val hidden = WindowLayout.DEFAULT.copy(
            history = RegionSlot.HIDDEN, languages = RegionSlot.HIDDEN, services = RegionSlot.HIDDEN,
            showStatusBar = false,
        )
        val hosts = hidden.requiredHosts(PaneLayout.STACKED)
        assertEquals(WindowFeature.entries.toSet(), hosts.keys)
        assertTrue(hosts.values.all { it == FeatureHost.InputStrip })

        val statusOff = WindowLayout.DEFAULT.copy(showStatusBar = false).requiredHosts(PaneLayout.STACKED)
        assertEquals(FeatureHost.HistoryRowEnd, statusOff.getValue(WindowFeature.NOTIFICATIONS))
        assertEquals(
            FeatureHost.StatusBar,
            WindowLayout.DEFAULT.requiredHosts(PaneLayout.STACKED).getValue(WindowFeature.NOTIFICATIONS),
        )
        assertEquals(
            FeatureHost.ComparisonHeader,
            WindowLayout.DEFAULT.copy(panes = PaneLayout.COMPARISON)
                .requiredHosts(PaneLayout.COMPARISON).getValue(WindowFeature.SERVICE_SWITCH),
        )
        assertEquals(
            FeatureHost.RegionRow(WindowRegion.SERVICES),
            WindowLayout.DEFAULT.copy(panes = PaneLayout.COMPARISON)
                .requiredHosts(PaneLayout.STACKED).getValue(WindowFeature.SERVICE_SWITCH),
        )
    }

    // ---- Rules ----

    @Test
    fun `services controls are disabled under comparison only`() {
        everyLayout().forEach { layout ->
            layout.effectiveVariants().forEach { effective ->
                val availability = layout.servicesControlsAvailability(effective)
                if (effective == PaneLayout.COMPARISON) {
                    assertEquals(LayoutDisabledReason.COMPARISON_OWNS_SERVICES, availability.disabledReason)
                    assertFalse(availability.isEnabled)
                } else {
                    assertEquals(LayoutAvailability.Enabled, availability)
                    assertNull(availability.disabledReason)
                }
            }
        }
    }

    @Test
    fun `the strip cannot share a row`() {
        val strip = WindowLayout.DEFAULT.copy(serviceWidget = ServiceWidget.STRIP)
        assertEquals(
            LayoutAvailability.Disabled(LayoutDisabledReason.STRIP_NEEDS_OWN_ROW),
            strip.shareRowAvailability(WindowRegion.SERVICES, PaneLayout.STACKED),
        )
        assertEquals(
            LayoutAvailability.Enabled,
            WindowLayout.DEFAULT.shareRowAvailability(WindowRegion.SERVICES, PaneLayout.STACKED),
        )
        // The other regions can always share, whatever the widget is.
        listOf(WindowRegion.HISTORY, WindowRegion.LANGUAGES).forEach { region ->
            PaneLayout.entries.forEach { panes ->
                assertEquals(LayoutAvailability.Enabled, strip.shareRowAvailability(region, panes))
            }
        }
        // Under comparison the services region does not exist, which outranks the strip reason.
        assertEquals(
            LayoutAvailability.Disabled(LayoutDisabledReason.COMPARISON_OWNS_SERVICES),
            strip.shareRowAvailability(WindowRegion.SERVICES, PaneLayout.COMPARISON),
        )
    }

    @Test
    fun `appearance is enabled only for the strip outside comparison`() {
        everyLayout().forEach { layout ->
            layout.effectiveVariants().forEach { effective ->
                val expected = when {
                    effective == PaneLayout.COMPARISON ->
                        LayoutAvailability.Disabled(LayoutDisabledReason.COMPARISON_OWNS_SERVICES)
                    layout.serviceWidget == ServiceWidget.PICKER ->
                        LayoutAvailability.Disabled(LayoutDisabledReason.APPEARANCE_NEEDS_STRIP)
                    else -> LayoutAvailability.Enabled
                }
                assertEquals(expected, layout.appearanceAvailability(effective), "$layout / $effective")
            }
        }
    }

    @Test
    fun `slot accessors read and write the matching region`() {
        WindowRegion.entries.forEach { region ->
            RegionSlot.entries.forEach { slot ->
                val moved = WindowLayout.DEFAULT.withSlot(region, slot)
                assertEquals(slot, moved.slotOf(region))
                WindowRegion.entries.filter { it != region }.forEach {
                    assertEquals(WindowLayout.DEFAULT.slotOf(it), moved.slotOf(it), "$it untouched")
                }
            }
        }
        assertNotEquals(WindowLayout.DEFAULT, WindowLayout.DEFAULT.withSlot(WindowRegion.HISTORY, RegionSlot.BOTTOM))
    }
}

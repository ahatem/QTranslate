package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** Every value the new model can hold: 3 x 4 x 3 x 4 x 2 x 3 x 2 x 2 x 2. The language row never hides. */
    private fun everyLayout(): List<WindowLayout> =
        PaneLayout.entries.flatMap { panes ->
            RegionSlot.entries.flatMap { history ->
                WindowRegion.LANGUAGES.allowedSlots.flatMap { languages ->
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
            // The language row never hides, so a hidden v10 bar gets the slot a visible one would.
            val expectedLanguages = if (panes == PaneLayout.SIDE_BY_SIDE) RegionSlot.TOP else RegionSlot.ABOVE_RESULTS
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
            languages = RegionSlot.BOTTOM,
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
                        if (WindowRegion.SERVICES in row) {
                            assertEquals(listOf(WindowRegion.SERVICES), row, "the selector is alone: $layout")
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
    fun `shared rows merge history and languages but never the service selector`() {
        val shared = WindowLayout(
            history = RegionSlot.TOP, languages = RegionSlot.TOP, services = RegionSlot.TOP, shareRows = true,
        )
        val historyAndLanguagesThenServices = listOf(
            listOf(WindowRegion.HISTORY, WindowRegion.LANGUAGES),
            listOf(WindowRegion.SERVICES),
        )
        assertEquals(historyAndLanguagesThenServices, shared.rowPlan(PaneLayout.STACKED)[RegionSlot.TOP])
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
            history = RegionSlot.HIDDEN, services = RegionSlot.HIDDEN, showStatusBar = false,
        )
        val hosts = hidden.requiredHosts(PaneLayout.STACKED)
        assertEquals(WindowFeature.entries.toSet(), hosts.keys)
        // The language row never hides, so it keeps its own commands whatever else is hidden.
        assertEquals(FeatureHost.RegionRow(WindowRegion.LANGUAGES), hosts.getValue(WindowFeature.LANGUAGE_PAIR))
        assertEquals(FeatureHost.RegionRow(WindowRegion.LANGUAGES), hosts.getValue(WindowFeature.TRANSLATE_ACTION))
        assertTrue(
            hosts.filterKeys { it != WindowFeature.LANGUAGE_PAIR && it != WindowFeature.TRANSLATE_ACTION }
                .values.all { it == FeatureHost.InputStrip },
        )

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
    fun `the service selector cannot share a row`() {
        val strip = WindowLayout.DEFAULT.copy(serviceWidget = ServiceWidget.STRIP)
        assertEquals(
            LayoutAvailability.Disabled(LayoutDisabledReason.SELECTOR_NEEDS_OWN_ROW),
            strip.shareRowAvailability(WindowRegion.SERVICES, PaneLayout.STACKED),
        )
        assertEquals(
            LayoutAvailability.Disabled(LayoutDisabledReason.SELECTOR_NEEDS_OWN_ROW),
            WindowLayout.DEFAULT.shareRowAvailability(WindowRegion.SERVICES, PaneLayout.STACKED),
        )
        // The other regions can always share, whatever the widget is.
        listOf(WindowRegion.HISTORY, WindowRegion.LANGUAGES).forEach { region ->
            PaneLayout.entries.forEach { panes ->
                assertEquals(LayoutAvailability.Enabled, strip.shareRowAvailability(region, panes))
            }
        }
        // Under comparison the services region does not exist, which outranks the selector reason.
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
            region.allowedSlots.forEach { slot ->
                val moved = WindowLayout.DEFAULT.withSlot(region, slot)
                assertEquals(slot, moved.slotOf(region))
                WindowRegion.entries.filter { it != region }.forEach {
                    assertEquals(WindowLayout.DEFAULT.slotOf(it), moved.slotOf(it), "$it untouched")
                }
            }
        }
        assertNotEquals(WindowLayout.DEFAULT, WindowLayout.DEFAULT.withSlot(WindowRegion.HISTORY, RegionSlot.BOTTOM))
    }

    // ---- The language row never hides ----

    @Test
    fun `only the language row is barred from the hidden slot`() {
        assertEquals(
            listOf(RegionSlot.TOP, RegionSlot.ABOVE_RESULTS, RegionSlot.BOTTOM),
            WindowRegion.LANGUAGES.allowedSlots,
        )
        listOf(WindowRegion.HISTORY, WindowRegion.SERVICES).forEach {
            assertEquals(RegionSlot.entries, it.allowedSlots.sortedBy { slot -> slot.ordinal }, "$it")
        }
    }

    @Test
    fun `a write that hides the language row is impossible`() {
        assertFailsWith<IllegalArgumentException> { WindowLayout(languages = RegionSlot.HIDDEN) }
        assertFailsWith<IllegalArgumentException> { WindowLayout.DEFAULT.copy(languages = RegionSlot.HIDDEN) }
        assertFailsWith<IllegalArgumentException> {
            WindowLayout.DEFAULT.withSlot(WindowRegion.LANGUAGES, RegionSlot.HIDDEN)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowRecipe(languages = RegionSlot.HIDDEN).applyTo(WindowLayout.DEFAULT)
        }
    }

    @Test
    fun `a stored hidden language row loads as above results`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val stored = """{"panes":"SIDE_BY_SIDE","history":"BOTTOM","languages":"HIDDEN","services":"TOP"}"""
        assertEquals(
            WindowLayout(panes = PaneLayout.SIDE_BY_SIDE, history = RegionSlot.BOTTOM, services = RegionSlot.TOP),
            json.decodeFromString<WindowLayout>(stored),
        )
        // Inside a saved profile and a whole configuration too, and it is written back as a legal value.
        val profile = json.decodeFromString<LayoutProfile>("""{"name":"Old","layout":$stored}""")
        assertEquals(RegionSlot.ABOVE_RESULTS, profile.layout.languages)
        val config = json.decodeFromString<Configuration>("""{"windowLayout":$stored}""")
        assertEquals(RegionSlot.ABOVE_RESULTS, config.windowLayout.languages)
        val written = Json { encodeDefaults = true }.encodeToString(WindowLayout.serializer(), config.windowLayout)
        assertTrue(""""languages":"ABOVE_RESULTS"""" in written, written)
    }

    @Test
    fun `every stored slot of the language row other than hidden is read as written`() {
        val json = Json
        WindowRegion.LANGUAGES.allowedSlots.forEach { slot ->
            val layout = WindowLayout.DEFAULT.copy(languages = slot)
            assertEquals(layout, json.decodeFromString<WindowLayout>(json.encodeToString(WindowLayout.serializer(), layout)))
        }
    }

    @Test
    fun `the language row is always hosted in a row of its own whatever else is hidden`() {
        everyLayout().forEach { layout ->
            layout.effectiveVariants().forEach { effective ->
                assertEquals(
                    FeatureHost.RegionRow(WindowRegion.LANGUAGES),
                    layout.requiredHosts(effective).getValue(WindowFeature.LANGUAGE_PAIR),
                )
                assertTrue(
                    WindowRegion.LANGUAGES in layout.rowPlan(effective).values.flatten().flatten(),
                    "the language row is in the plan: $layout / $effective",
                )
            }
        }
    }

    // ---- The second result ----

    @Test
    fun `the second result is below by default and a file without the field decodes to below`() {
        assertEquals(SecondResultPlacement.BELOW, WindowLayout.DEFAULT.secondResult)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val stored = """{"panes":"SIDE_BY_SIDE","history":"TOP","languages":"TOP","services":"BOTTOM"}"""
        val decoded = json.decodeFromString<WindowLayout>(stored)
        assertEquals(SecondResultPlacement.BELOW, decoded.secondResult)
        assertEquals(PaneLayout.SIDE_BY_SIDE, decoded.panes)
        // The same inside a profile and a whole configuration, which is how a released file holds it.
        assertEquals(
            SecondResultPlacement.BELOW,
            json.decodeFromString<LayoutProfile>("""{"name":"Old","layout":$stored}""").layout.secondResult,
        )
        assertEquals(
            SecondResultPlacement.BELOW,
            json.decodeFromString<Configuration>("""{"windowLayout":$stored}""").windowLayout.secondResult,
        )
    }

    @Test
    fun `every placement survives a round trip`() {
        val json = Json { encodeDefaults = true }
        SecondResultPlacement.entries.forEach { placement ->
            val layout = WindowLayout.DEFAULT.copy(secondResult = placement)
            assertEquals(
                layout,
                json.decodeFromString<WindowLayout>(json.encodeToString(WindowLayout.serializer(), layout)),
                "$placement",
            )
        }
    }

    @Test
    fun `the placement that is built follows the panes and never rewrites the stored one`() {
        val expected = mapOf(
            PaneLayout.STACKED to mapOf(
                SecondResultPlacement.BELOW to SecondResultPlacement.BELOW,
                SecondResultPlacement.WITH_RESULT to SecondResultPlacement.WITH_RESULT,
                SecondResultPlacement.OWN_COLUMN to SecondResultPlacement.BELOW,
            ),
            PaneLayout.SIDE_BY_SIDE to mapOf(
                SecondResultPlacement.BELOW to SecondResultPlacement.BELOW,
                SecondResultPlacement.WITH_RESULT to SecondResultPlacement.WITH_RESULT,
                SecondResultPlacement.OWN_COLUMN to SecondResultPlacement.OWN_COLUMN,
            ),
            PaneLayout.COMPARISON to mapOf(
                SecondResultPlacement.BELOW to SecondResultPlacement.BELOW,
                SecondResultPlacement.WITH_RESULT to SecondResultPlacement.BELOW,
                SecondResultPlacement.OWN_COLUMN to SecondResultPlacement.BELOW,
            ),
        )
        PaneLayout.entries.forEach { panes ->
            SecondResultPlacement.entries.forEach { stored ->
                val layout = WindowLayout(panes = panes, secondResult = stored)
                assertEquals(expected.getValue(panes).getValue(stored), layout.effectiveSecondResult(panes), "$panes / $stored")
                assertEquals(stored, layout.secondResult, "the stored value is not rewritten")
            }
        }
        // A requested Comparison that is not eligible builds Stacked panes, so Stacked's rules apply.
        val ineligible = WindowLayout(panes = PaneLayout.COMPARISON, secondResult = SecondResultPlacement.WITH_RESULT)
        val built = ineligible.effectivePanes(isComparisonEligible = false)
        assertEquals(SecondResultPlacement.WITH_RESULT, ineligible.effectiveSecondResult(built))
    }

    @Test
    fun `availability of each placement for every pane layout and second result type`() {
        PaneLayout.entries.forEach { panes ->
            ExtraOutputType.entries.forEach { type ->
                SecondResultPlacement.entries.forEach { option ->
                    val actual = WindowLayout.DEFAULT.secondResultAvailability(option, panes, type)
                    val expected: LayoutAvailability = when {
                        type == ExtraOutputType.None ->
                            LayoutAvailability.Disabled(LayoutDisabledReason.SECOND_RESULT_NEEDS_TYPE)
                        option == SecondResultPlacement.BELOW -> LayoutAvailability.Enabled
                        panes == PaneLayout.COMPARISON ->
                            LayoutAvailability.Disabled(LayoutDisabledReason.COMPARISON_KEEPS_SECOND_RESULT_BELOW)
                        option == SecondResultPlacement.OWN_COLUMN && panes == PaneLayout.STACKED ->
                            LayoutAvailability.Disabled(LayoutDisabledReason.OWN_COLUMN_NEEDS_SIDE_BY_SIDE)
                        else -> LayoutAvailability.Enabled
                    }
                    assertEquals(expected, actual, "$panes / $type / $option")
                }
            }
        }
    }

    @Test
    fun `with no second result chosen every control is disabled and the stored placement is kept`() {
        PaneLayout.entries.forEach { panes ->
            SecondResultPlacement.entries.forEach { stored ->
                val layout = WindowLayout(panes = panes, secondResult = stored)
                SecondResultPlacement.entries.forEach { option ->
                    assertEquals(
                        LayoutDisabledReason.SECOND_RESULT_NEEDS_TYPE,
                        layout.secondResultAvailability(option, panes, ExtraOutputType.None).disabledReason,
                        "$panes / $stored / $option",
                    )
                }
                assertEquals(stored, layout.secondResult)
            }
        }
    }

    @Test
    fun `every placement an available option names is one the panes build as asked`() {
        PaneLayout.entries.forEach { panes ->
            SecondResultPlacement.entries.forEach { option ->
                val layout = WindowLayout(panes = panes, secondResult = option)
                val available = layout.secondResultAvailability(option, panes, ExtraOutputType.BackwardTranslate).isEnabled
                assertEquals(available, layout.effectiveSecondResult(panes) == option, "$panes / $option")
            }
        }
    }

    @Test
    fun `a starting point leaves the second result as it was`() {
        WindowPreset.entries.forEach { preset ->
            SecondResultPlacement.entries.forEach { placement ->
                val before = WindowLayout.DEFAULT.copy(secondResult = placement)
                assertEquals(placement, preset.applyTo(before).secondResult, "$preset")
            }
        }
    }
}

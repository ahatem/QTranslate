package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The v11 → v12 region model: total migration, reachable features, and partial starting points. */
class WorkspaceLayoutTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun everyWorkspaceLayout(): List<WorkspaceLayout> =
        PaneLayout.entries.flatMap { panes ->
            HistoryPlacement.entries.flatMap { history ->
                LanguagePlacement.entries.flatMap { languages ->
                    ServicePlacement.entries.flatMap { services ->
                        ServiceSelectorAppearance.entries.flatMap { appearance ->
                            listOf(true, false).map { status ->
                                WorkspaceLayout(panes, history, languages, services, appearance, status)
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `every v11 config migrates deterministically and keeps its appearance`() {
        val inputs = WorkspaceLayoutMigration.allV11Input()
        assertEquals(90, inputs.size, "30 geometries x 3 appearances")
        inputs.forEach { (layout, appearance) ->
            val migrated = WorkspaceLayoutMigration.fromV11(layout, appearance)
            assertEquals(migrated, WorkspaceLayoutMigration.fromV11(layout, appearance))
            assertEquals(appearance, migrated.appearance, "v11's dropped value is recovered")
            assertEquals(layout.paneLayout, migrated.panes)
            assertEquals(layout.showStatusBar, migrated.showStatusBar)
            assertEquals(
                layout.serviceSelector == ServiceSelector.BOTTOM_STRIP,
                migrated.services == ServicePlacement.BOTTOM_STRIP,
            )
        }
    }

    @Test
    fun `v11 toolbar styles map to history placements without losing a feature`() {
        fun historyOf(style: ToolbarStyle) = WorkspaceLayoutMigration.fromV11(
            LayoutConfig(PaneLayout.STACKED, style, ServiceSelector.PICKER, true),
            ServiceSelectorAppearance.ICONS_AND_TEXT,
        ).history
        assertEquals(HistoryPlacement.TOP_ROW, historyOf(ToolbarStyle.FULL))
        assertEquals(HistoryPlacement.COMBINED, historyOf(ToolbarStyle.COMPACT))
        // MINIMAL used to hide the bar and its buttons; relocation keeps them visible.
        assertEquals(HistoryPlacement.RELOCATED, historyOf(ToolbarStyle.MINIMAL))
    }

    @Test
    fun `every reachable arrangement has a visible route to every feature`() {
        val layouts = everyWorkspaceLayout()
        assertEquals(432, layouts.size)
        layouts.forEach { layout ->
            val routes = layout.featureRoutes()
            LayoutFeature.entries.forEach { feature ->
                assertTrue(
                    routes[feature] != null,
                    "$layout must give $feature a visible route, not silence it",
                )
            }
        }
    }

    @Test
    fun `inline history with a relocated language row resolves to a top history row`() {
        val asked = WorkspaceLayout(
            history = HistoryPlacement.COMBINED,
            languages = LanguagePlacement.RELOCATED,
        )
        assertEquals(HistoryPlacement.TOP_ROW, asked.effective.history)
        assertEquals(LanguagePlacement.RELOCATED, asked.effective.languages)
        // The asked-for values are not rewritten in storage.
        assertEquals(HistoryPlacement.COMBINED, asked.history)
        assertEquals(
            FeatureRoute.HISTORY_ROW_TOP,
            asked.featureRoutes()[LayoutFeature.BACK_FORWARD],
        )
    }

    @Test
    fun `the classic card is the classic QTranslate arrangement`() {
        val classic = WorkspacePreset.CLASSIC.recipe.applyTo(WorkspaceLayout.DEFAULT)
        assertEquals(PaneLayout.STACKED, classic.panes)
        assertEquals(HistoryPlacement.TOP_ROW, classic.history)
        assertEquals(LanguagePlacement.ROW, classic.languages)
        assertEquals(ServicePlacement.BOTTOM_STRIP, classic.services)
        assertEquals(ServiceSelectorAppearance.ICONS_AND_TEXT, classic.appearance)
        assertEquals(FeatureRoute.BOTTOM_STRIP, classic.featureRoutes()[LayoutFeature.SERVICE_SWITCH])
        assertEquals(FeatureRoute.HISTORY_ROW_TOP, classic.featureRoutes()[LayoutFeature.OCR_AND_DOCUMENT])
    }

    @Test
    fun `a card changes only the regions it names`() {
        val custom = WorkspaceLayout(
            panes = PaneLayout.SIDE_BY_SIDE,
            history = HistoryPlacement.TOP_ROW,
            languages = LanguagePlacement.ROW,
            services = ServicePlacement.BOTTOM_STRIP,
            appearance = ServiceSelectorAppearance.TEXT_ONLY,
            showStatusBar = false,
        )
        val compact = WorkspacePreset.COMPACT.recipe.applyTo(custom)
        assertEquals(HistoryPlacement.COMBINED, compact.history)
        assertEquals(PaneLayout.SIDE_BY_SIDE, compact.panes, "compact leaves the pane choice alone")
        assertEquals(ServicePlacement.BOTTOM_STRIP, compact.services, "compact keeps the bottom strip")
        assertEquals(ServiceSelectorAppearance.TEXT_ONLY, compact.appearance)
        assertEquals(false, compact.showStatusBar)
    }

    @Test
    fun `the bridge keeps the old description in step`() {
        val layout = WorkspacePreset.CLASSIC.recipe.applyTo(WorkspaceLayout.DEFAULT)
        val config = Configuration.DEFAULT.withWorkspaceLayout(layout)
        assertEquals(layout, config.workspaceLayout)
        assertEquals(LayoutPresetIds.CLASSIC, config.layoutPresetId)
        assertEquals(ServiceSelectorStyle.CLASSIC, config.serviceSelectorStyle)
        assertEquals(ServiceSelectorAppearance.ICONS_AND_TEXT, config.serviceSelectorAppearance)
        assertTrue(config.toolbarVisibility.isHistoryBarVisible)
        assertTrue(config.toolbarVisibility.isServicesPanelVisible)
    }

    @Test
    fun `a v11 file on disk upgrades to a region layout`() {
        val payload = """
                {
                  "configVersion": 11,
                  "layoutPresetId": "side_by_side",
                  "toolbarVisibility": {
                    "isHistoryBarVisible": true,
                    "isLanguageBarVisible": true,
                    "isServicesPanelVisible": true,
                    "isStatusBarVisible": true
                  },
                  "serviceSelectorStyle": "CLASSIC",
                  "serviceSelectorAppearance": "ICONS_ONLY",
                  "layoutConfig": {
                    "paneLayout": "SIDE_BY_SIDE",
                    "toolbarStyle": "FULL",
                    "serviceSelector": "BOTTOM_STRIP",
                    "showStatusBar": true
                  }
                }
            """.trimIndent()
            val decoded = ConfigMigrator.migrate(json.decodeFromString<Configuration>(payload), logger)
            assertEquals(12, decoded.configVersion)
            assertEquals(PaneLayout.SIDE_BY_SIDE, decoded.workspaceLayout.panes)
            assertEquals(HistoryPlacement.TOP_ROW, decoded.workspaceLayout.history)
            assertEquals(ServicePlacement.BOTTOM_STRIP, decoded.workspaceLayout.services)
            assertEquals(ServiceSelectorAppearance.ICONS_ONLY, decoded.workspaceLayout.appearance)
    }
}

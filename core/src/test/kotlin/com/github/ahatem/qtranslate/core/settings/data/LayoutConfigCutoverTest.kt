package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v11 cutover as a user actually experiences it: a file on disk written by the released build.
 *
 * `LayoutConfigMigrationTest` proves the mapping given a [Configuration] object. It cannot catch the
 * failure one step earlier, where the JSON is decoded: a field this branch renamed or dropped from
 * the data class would be discarded by `ignoreUnknownKeys` before the mapping ever ran, and the
 * user would silently receive the default arrangement instead of theirs. So these payloads are
 * decoded with the same `Json` settings production uses, and the assertions are against values
 * written in the payload rather than against whatever the decode produced.
 */
class LayoutConfigCutoverTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /** Matches `AppDependencies`; a stricter parser here would test something we do not ship. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun payload(
        layoutPresetId: String = LayoutPresetIds.CLASSIC,
        history: Boolean = true,
        language: Boolean = true,
        services: Boolean = true,
        status: Boolean = true,
        selectorStyle: String = "ENHANCED",
        appearance: String = "ICONS_AND_TEXT",
    ): String = """
        {
          "configVersion": 10,
          "layoutPresetId": "$layoutPresetId",
          "toolbarVisibility": {
            "isHistoryBarVisible": $history,
            "isLanguageBarVisible": $language,
            "isServicesPanelVisible": $services,
            "isStatusBarVisible": $status
          },
          "serviceSelectorStyle": "$selectorStyle",
          "serviceSelectorAppearance": "$appearance"
        }
    """.trimIndent()

    private fun load(payload: String): Configuration =
        ConfigMigrator.migrate(json.decodeFromString<Configuration>(payload), logger)

    private fun LayoutGeometry.toConfig(): LayoutConfig =
        LayoutConfig(paneLayout, toolbarStyle, serviceSelector ?: ServiceSelector.PICKER, showStatusBar)

    @Test
    fun `a released profile gains its four layout values from the ones it already had`() {
        val migrated = load(
            payload(
                layoutPresetId = LayoutPresetIds.SIDE_BY_SIDE,
                status = false,
                selectorStyle = "CLASSIC",
                appearance = "TEXT_ONLY",
            )
        )

        // The released v10 profile migrates through v11 (four layout values) to v12 (regions).
        assertEquals(12, migrated.configVersion)
        assertEquals(
            LayoutConfig(
                paneLayout = PaneLayout.SIDE_BY_SIDE,
                toolbarStyle = ToolbarStyle.FULL,
                serviceSelector = ServiceSelector.BOTTOM_STRIP,
                showStatusBar = false,
            ),
            migrated.layoutConfig
        )
        assertEquals(
            WorkspaceLayout(
                panes = PaneLayout.SIDE_BY_SIDE,
                history = HistoryPlacement.TOP_ROW,
                languages = LanguagePlacement.ROW,
                services = ServicePlacement.BOTTOM_STRIP,
                appearance = ServiceSelectorAppearance.TEXT_ONLY,
                showStatusBar = false,
            ),
            migrated.workspaceLayout,
        )

        // The older description is still there and still says the same thing: the settings page
        // writes it until it moves over, so the two must not drift apart in this release.
        assertEquals(LayoutPresetIds.SIDE_BY_SIDE, migrated.layoutPresetId)
        assertEquals(ServiceSelectorStyle.CLASSIC, migrated.serviceSelectorStyle)
        assertEquals(ServiceSelectorAppearance.TEXT_ONLY, migrated.serviceSelectorAppearance)
        assertTrue(!migrated.toolbarVisibility.isStatusBarVisible, "the footer switch must survive decoding")
    }

    @Test
    fun `every chrome combination the released build can write decodes and maps as written`() {
        // Ninety-six payloads, each asserted against the values that were *written* rather than
        // against the decoded ones, so a field silently dropped by the parser fails here.
        listOf(LayoutPresetIds.CLASSIC, LayoutPresetIds.SIDE_BY_SIDE, LayoutPresetIds.COMPARISON)
            .forEach { id ->
                (0 until 16).forEach { bits ->
                    val history = bits and 1 == 0
                    val language = bits and 2 == 0
                    val services = bits and 4 == 0
                    val status = bits and 8 == 0
                    ServiceSelectorStyle.values().forEach { style ->
                        val migrated = load(
                            payload(
                                layoutPresetId = id,
                                history = history,
                                language = language,
                                services = services,
                                status = status,
                                selectorStyle = style.name,
                            )
                        )

                        // What was decoded, first: the switches are the inputs the mapping needs.
                        assertEquals(
                            ToolbarVisibility(history, language, services, status),
                            migrated.toolbarVisibility,
                            "decode lost a chrome switch for $id/$bits/${style.name}"
                        )
                        assertEquals(id, migrated.layoutPresetId)
                        assertEquals(style, migrated.serviceSelectorStyle)

                        // Then what the mapping made of them.
                        assertEquals(
                            LayoutConfigMigration.fromV10(
                                layoutPresetId = id,
                                toolbarVisibility = ToolbarVisibility(history, language, services, status),
                                serviceSelectorStyle = style,
                            ),
                            migrated.layoutConfig,
                            "the stored values and the mapping disagree for $id/$bits/${style.name}"
                        )
                    }
                }
            }
    }

    @Test
    fun `the same file migrated twice is the same file`() {
        val once = load(payload(layoutPresetId = LayoutPresetIds.COMPARISON, language = false))
        val twice = ConfigMigrator.migrate(once, logger)
        assertEquals(once, twice)
        assertEquals(once.layoutConfig, twice.layoutConfig)
    }

    @Test
    fun `a configuration this build wrote is not re-mapped over`() {
        // A current file must keep what it says, even where the region values are not the ones the
        // mapping would have produced from the older fields still sitting beside them. This build
        // writes v12, so a v12 file is already current and must pass through the migrator untouched.
        val handEdited = Configuration.DEFAULT.copy(
            configVersion = 12,
            layoutConfig = LayoutConfig(
                paneLayout = PaneLayout.COMPARISON,
                toolbarStyle = ToolbarStyle.MINIMAL,
                serviceSelector = ServiceSelector.BOTTOM_STRIP,
                showStatusBar = false,
            ),
            workspaceLayout = WorkspaceLayout(
                panes = PaneLayout.COMPARISON,
                history = HistoryPlacement.RELOCATED,
                languages = LanguagePlacement.ROW,
                services = ServicePlacement.BOTTOM_STRIP,
                appearance = ServiceSelectorAppearance.TEXT_ONLY,
                showStatusBar = false,
            ),
        )

        assertEquals(handEdited, ConfigMigrator.migrate(handEdited, logger))
    }

    @Test
    fun `an installation left exactly as shipped keeps the window it has today`() {
        // The regression that matters most: the upgrade must not move anybody's window.
        val migrated = load(payload())
        assertEquals(LayoutPreset.CLASSIC.config, migrated.layoutConfig)
        assertEquals(LayoutPreset.CLASSIC, migrated.layoutConfig.matchingPreset())
    }

    @Test
    fun `writing the four values keeps the older ones describing the same window`() {
        // The settings page writes the new values; the window is still built from the old ones for
        // now, so the two have to agree or the page would silently do nothing.
        LayoutGeometry.all().forEach { geometry ->
            val written = geometry.toConfig()
            val configuration = Configuration.DEFAULT.withLayoutConfig(written)
            val readBack = LayoutConfigMigration.fromV10(configuration)

            // COMPACT is the one value the old switches cannot say -- see withLayoutConfig.
            val expected = if (written.toolbarStyle == ToolbarStyle.COMPACT) {
                written.copy(toolbarStyle = ToolbarStyle.MINIMAL)
            } else {
                written
            }
            assertEquals(expected.geometry, readBack.geometry, "writing $written must survive as $expected")
            // And what the page stored is exactly what was asked for.
            assertEquals(written, configuration.layoutConfig)
        }
    }

    @Test
    fun `compact survives as itself in the value it is written to`() {
        // What the page stores is exact; only the interim bridge to the old fields loses it.
        val compact = LayoutConfig(PaneLayout.STACKED, ToolbarStyle.COMPACT, ServiceSelector.PICKER, true)
        val configuration = Configuration.DEFAULT.withLayoutConfig(compact)
        assertEquals(compact, configuration.layoutConfig)
        assertEquals(ToolbarStyle.MINIMAL, LayoutConfigMigration.fromV10(configuration).toolbarStyle)
    }
}

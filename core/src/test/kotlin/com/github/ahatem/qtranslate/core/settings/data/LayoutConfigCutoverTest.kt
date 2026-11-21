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

        assertEquals(11, migrated.configVersion)
        assertEquals(
            LayoutConfig(
                paneLayout = PaneLayout.SIDE_BY_SIDE,
                toolbarStyle = ToolbarStyle.FULL,
                serviceSelector = ServiceSelector.BOTTOM_STRIP,
                showStatusBar = false,
            ),
            migrated.layoutConfig
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
        // A current file must keep what it says, even where the four values are not the ones the
        // mapping would have produced from the older fields still sitting beside them.
        val handEdited = Configuration.DEFAULT.copy(
            configVersion = 11,
            layoutConfig = LayoutConfig(
                paneLayout = PaneLayout.COMPARISON,
                toolbarStyle = ToolbarStyle.MINIMAL,
                serviceSelector = ServiceSelector.BOTTOM_STRIP,
                showStatusBar = false,
            )
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
}

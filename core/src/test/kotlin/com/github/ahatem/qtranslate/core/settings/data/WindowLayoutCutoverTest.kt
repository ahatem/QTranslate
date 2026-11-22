package com.github.ahatem.qtranslate.core.settings.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v13 cutover as a user actually experiences it: a file on disk written by a released build.
 *
 * `WindowLayoutTest` proves the mapping given a [Configuration] object. It cannot catch the failure
 * one step earlier, where the JSON is decoded: a field renamed or dropped from the data class is
 * discarded by `ignoreUnknownKeys` before the mapping ever runs, and the user would silently receive
 * the default arrangement instead of theirs. So these payloads are decoded with the same `Json`
 * settings production uses, and the assertions are against values written in the payload rather
 * than against whatever the decode produced.
 *
 * Versions 11 and 12 stored their own descriptions of the window (`layoutConfig`,
 * `workspaceLayout`), which this build no longer has. Those releases kept the v10 fields in step, so
 * every one of the three versions maps from the v10 fields, and the payloads below carry a
 * deliberately contradictory v11/v12 value to prove it is ignored.
 */
class WindowLayoutCutoverTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /** Matches `AppDependencies`; a stricter parser here would test something we do not ship. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val releasedVersions = listOf(10, 11, 12)

    private fun payload(
        version: Int = 10,
        layoutPresetId: String = LayoutPresetIds.CLASSIC,
        history: Boolean = true,
        language: Boolean = true,
        services: Boolean = true,
        status: Boolean = true,
        selectorStyle: String = "ENHANCED",
        appearance: String = "ICONS_AND_TEXT",
    ): String {
        // What the v11 and v12 builds wrote beside the v10 fields. Chosen to disagree with them, so
        // a migration that read these instead would fail every assertion below.
        val laterFields = buildString {
            if (version >= 11) {
                append(
                    """
                    ,
                      "layoutConfig": {
                        "paneLayout": "COMPARISON",
                        "toolbarStyle": "MINIMAL",
                        "serviceSelector": "BOTTOM_STRIP",
                        "showStatusBar": false
                      }
                    """.trimIndent()
                )
            }
            if (version >= 12) {
                append(
                    """
                    ,
                      "workspaceLayout": {
                        "panes": "COMPARISON",
                        "history": "RELOCATED",
                        "languages": "RELOCATED",
                        "services": "RELOCATED",
                        "appearance": "ICONS_ONLY",
                        "showStatusBar": false
                      }
                    """.trimIndent()
                )
            }
        }
        return """
            {
              "configVersion": $version,
              "layoutPresetId": "$layoutPresetId",
              "toolbarVisibility": {
                "isHistoryBarVisible": $history,
                "isLanguageBarVisible": $language,
                "isServicesPanelVisible": $services,
                "isStatusBarVisible": $status
              },
              "serviceSelectorStyle": "$selectorStyle",
              "serviceSelectorAppearance": "$appearance"$laterFields
            }
        """.trimIndent()
    }

    private fun load(payload: String): Configuration =
        ConfigMigrator.migrate(json.decodeFromString<Configuration>(payload), logger)

    @Test
    fun `this build writes version 13`() {
        assertEquals(13, ConfigMigrator.CURRENT_VERSION)
        assertEquals(13, Configuration.DEFAULT.configVersion)
    }

    @Test
    fun `an installation left exactly as shipped keeps the window it has today`() {
        // The regression that matters most: the upgrade must not move anybody's window.
        releasedVersions.forEach { version ->
            val migrated = load(payload(version = version))
            assertEquals(13, migrated.configVersion)
            assertEquals(WindowLayout.DEFAULT, migrated.windowLayout, "a default v$version file")
        }
    }

    @Test
    fun `a released profile gains its regions from the v10 values it already had`() {
        releasedVersions.forEach { version ->
            val migrated = load(
                payload(
                    version = version,
                    layoutPresetId = LayoutPresetIds.SIDE_BY_SIDE,
                    services = false,
                    status = false,
                    selectorStyle = "CLASSIC",
                    appearance = "TEXT_ONLY",
                )
            )

            assertEquals(13, migrated.configVersion)
            assertEquals(
                WindowLayout(
                    panes = PaneLayout.SIDE_BY_SIDE,
                    history = RegionSlot.TOP,
                    languages = RegionSlot.TOP,
                    services = RegionSlot.HIDDEN,
                    serviceWidget = ServiceWidget.STRIP,
                    stripAppearance = ServiceSelectorAppearance.TEXT_ONLY,
                    shareRows = false,
                    showStatusBar = false,
                    density = Density.COMFORTABLE,
                ),
                migrated.windowLayout,
                "v$version",
            )

            // The frozen fields are still stored, unchanged, for a later release to drop.
            assertEquals(LayoutPresetIds.SIDE_BY_SIDE, migrated.layoutPresetId)
            assertEquals(ServiceSelectorStyle.CLASSIC, migrated.serviceSelectorStyle)
            assertEquals(ServiceSelectorAppearance.TEXT_ONLY, migrated.serviceSelectorAppearance)
            assertTrue(!migrated.toolbarVisibility.isStatusBarVisible, "the footer switch must survive decoding")
        }
    }

    @Test
    fun `every chrome combination a released build can write decodes and maps as written`() {
        // 288 payloads, each asserted against the values that were *written* rather than against
        // the decoded ones, so a field silently dropped by the parser fails here.
        releasedVersions.forEach { version ->
            listOf(LayoutPresetIds.CLASSIC, LayoutPresetIds.SIDE_BY_SIDE, LayoutPresetIds.COMPARISON).forEach { id ->
                (0 until 16).forEach { bits ->
                    val history = bits and 1 == 0
                    val language = bits and 2 == 0
                    val services = bits and 4 == 0
                    val status = bits and 8 == 0
                    ServiceSelectorStyle.entries.forEach { style ->
                        val label = "v$version/$id/$bits/${style.name}"
                        val migrated = load(
                            payload(
                                version = version,
                                layoutPresetId = id,
                                history = history,
                                language = language,
                                services = services,
                                status = status,
                                selectorStyle = style.name,
                            )
                        )

                        assertEquals(
                            ToolbarVisibility(history, language, services, status),
                            migrated.toolbarVisibility,
                            "decode lost a chrome switch for $label",
                        )
                        assertEquals(
                            WindowLayoutMigration.fromV10(
                                layoutPresetId = id,
                                toolbarVisibility = ToolbarVisibility(history, language, services, status),
                                serviceSelectorStyle = style,
                                serviceSelectorAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT,
                            ),
                            migrated.windowLayout,
                            "the stored values and the mapping disagree for $label",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the same file migrated twice is the same file`() {
        val once = load(payload(version = 12, layoutPresetId = LayoutPresetIds.COMPARISON, language = false))
        assertEquals(once, ConfigMigrator.migrate(once, logger))
    }

    @Test
    fun `a configuration this build wrote is not re-mapped over`() {
        // A current file keeps what it says, even where its regions are not what the mapping
        // would have made of the frozen fields sitting beside them.
        val handEdited = Configuration.DEFAULT.copy(
            layoutPresetId = LayoutPresetIds.CLASSIC,
            windowLayout = WindowLayout(
                panes = PaneLayout.COMPARISON,
                history = RegionSlot.BOTTOM,
                languages = RegionSlot.HIDDEN,
                shareRows = true,
                showStatusBar = false,
            ),
        )
        assertEquals(handEdited, ConfigMigrator.migrate(handEdited, logger))
    }

    @Test
    fun `a v11 and a v12 file on disk upgrade to 13 through the repository`() = runBlocking {
        releasedVersions.drop(1).forEach { version ->
            val directory = Files.createTempDirectory("qtranslate-cutover").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                // The released build's file, byte for byte: the raw JSON under the key it uses.
                val store = PreferenceDataStoreFactory.create(scope = scope) {
                    File(directory, "settings.preferences_pb")
                }
                store.edit { preferences ->
                    preferences[stringPreferencesKey(CONFIG_KEY)] = payload(
                        version = version,
                        layoutPresetId = LayoutPresetIds.SIDE_BY_SIDE,
                        history = false,
                    )
                }

                val loaded = SettingsRepository(directory, json, logger, dataStore = store).configuration.first()

                assertEquals(13, loaded.configVersion, "v$version")
                assertEquals(PaneLayout.SIDE_BY_SIDE, loaded.windowLayout.panes, "v$version")
                assertEquals(RegionSlot.HIDDEN, loaded.windowLayout.history, "v$version")
                assertEquals(RegionSlot.TOP, loaded.windowLayout.languages, "v$version")
                assertEquals(RegionSlot.BOTTOM, loaded.windowLayout.services, "v$version")
            } finally {
                scope.cancel()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `layout profiles are capped`() {
        val many = (1..LayoutProfile.MAX_PROFILES + 5).map { LayoutProfile("Profile $it", WindowLayout.DEFAULT) }
        val stored = Configuration.DEFAULT.withLayoutProfiles(many)
        assertEquals(LayoutProfile.MAX_PROFILES, stored.layoutProfiles.size)
        assertEquals(many.take(LayoutProfile.MAX_PROFILES), stored.layoutProfiles)
    }

    @Test
    fun `writing a layout changes only the layout`() {
        val layout = WindowLayout(panes = PaneLayout.SIDE_BY_SIDE, history = RegionSlot.HIDDEN)
        val written = Configuration.DEFAULT.withWindowLayout(layout)
        assertEquals(Configuration.DEFAULT.copy(windowLayout = layout), written)
    }

    private companion object {
        /** The key `SettingsRepository` stores the configuration under. */
        const val CONFIG_KEY = "configuration_json"
    }
}

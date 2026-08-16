package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Migration of configuration files taken from real installations, byte for byte.
 *
 * [ConfigMigratorTest] covers the migrator given a well-formed [Configuration]. It cannot catch
 * the failure that happens one step earlier: `SettingsRepository` deserializes the stored JSON
 * inside a `try` and falls back to [Configuration.DEFAULT] on `SerializationException`, logging
 * the error and continuing. A field this branch renamed or made non-optional would therefore not
 * crash — it would silently hand the user a factory-fresh configuration and drop every service
 * choice, preset and window position they had. Nothing in the UI would say so.
 *
 * These payloads are the `configuration_json` values from two installs of the last released
 * build, so they carry whatever the shipped app actually writes rather than what this branch
 * assumes it writes. The JSON is decoded with the same `Json` settings production uses.
 */
class RealConfigMigrationTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /** Matches `AppDependencies`; a stricter parser here would test something we do not ship. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun load(payload: String): Configuration =
        ConfigMigrator.migrate(json.decodeFromString<Configuration>(payload), logger)

    /**
     * The profile this release is being cut against, checked field by field.
     *
     * Deliberately exhaustive rather than spot-checked. Everything asserted here is something a
     * real person would notice losing, and the failure mode is a silent reset to defaults, so a
     * partial check would pass while handing them a factory-fresh app.
     */
    @Test
    fun `the maintainer's own 1_3_0 profile survives intact`() {
        val migrated = load(V3_REAL_PROFILE)

        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.configVersion)

        // Their five chosen services, rewritten into the composed form and still pointing at the
        // same providers. DeepL is the one to watch: it is the only non-Google choice.
        val selected = migrated.servicePresets.single().selectedServices
        assertTrue(
            selected[ServiceRole.TRANSLATOR]!!.contains("deepl"),
            "Translator should still be DeepL, was ${selected[ServiceRole.TRANSLATOR]}"
        )
        listOf(ServiceRole.TTS, ServiceRole.SPELL_CHECKER, ServiceRole.OCR, ServiceRole.DICTIONARY)
            .forEach { type ->
                assertTrue(
                    selected[type]!!.contains("google"),
                    "$type should still be Google, was ${selected[type]}"
                )
            }

        // The preset itself, not just its contents: a new id would orphan the active selection.
        assertEquals("72ea1742-e544-4dfb-890b-bd94a9ec406e", migrated.activeServicePresetId)
        assertEquals("72ea1742-e544-4dfb-890b-bd94a9ec406e", migrated.servicePresets.single().id)

        // Everything else they had set. An Arabic interface reverting to English, or 125% zoom
        // reverting to 100%, is exactly the kind of loss that reads as the app being broken.
        assertEquals("ar-SA", migrated.interfaceLanguage)
        assertEquals(125, migrated.uiScale)
        assertEquals("custom:qtranslate_light", migrated.themeId)
        assertEquals("ru", migrated.preferredTargetLanguage)
        assertEquals("en", migrated.preferredSourceLanguage)
        assertEquals(686, migrated.mainWindowSize?.width)
        assertEquals(559, migrated.mainWindowSize?.height)
        assertEquals(1077, migrated.mainWindowPosition?.x)
        assertEquals(464, migrated.mainWindowPosition?.y)
    }

    @Test
    fun `a v3 install keeps every service it had chosen`() {
        val migrated = load(V3_INSTALL)
        val selected = migrated.servicePresets.single().selectedServices

        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.configVersion)
        assertEquals("ai-plugin:default:ai-translator", selected[ServiceRole.TRANSLATOR])
        assertEquals("google-services:default:google-tts", selected[ServiceRole.TTS])
        assertEquals("google-services:default:google-spell-checker", selected[ServiceRole.SPELL_CHECKER])
        assertEquals("google-services:default:google-ocr", selected[ServiceRole.OCR])
        assertEquals("google-services:default:google-dictionary", selected[ServiceRole.DICTIONARY])
    }

    @Test
    fun `a v2 install migrates through every intermediate step`() {
        // Two versions behind, so this also proves the steps chain rather than only v3 to v4.
        val migrated = load(V2_INSTALL)

        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.configVersion)
        assertEquals(
            "google-services:default:google-translator",
            migrated.servicePresets.single().selectedServices[ServiceRole.TRANSLATOR]
        )
    }

    @Test
    fun `settings unrelated to services are carried through untouched`() {
        // The migration rewrites service ids. Everything else the user set must survive it,
        // and this is the assertion that fails if a step rebuilds the object from defaults.
        val migrated = load(V3_INSTALL)

        assertEquals("ar", migrated.preferredTargetLanguage)
        assertEquals("en", migrated.preferredSourceLanguage)
        assertEquals(686, migrated.mainWindowSize?.width)
        assertEquals(544, migrated.mainWindowSize?.height)
        assertEquals(1030, migrated.mainWindowPosition?.x)
        assertEquals(530, migrated.mainWindowPosition?.y)
        assertEquals(125, migrated.uiScale)
    }

    @Test
    fun `the active preset is still the one the migrated presets carry`() {
        // An id rewritten on the preset but not on the pointer leaves the app with no active
        // preset, which reads to the user as every setting having been forgotten.
        val migrated = load(V3_INSTALL)

        assertEquals("72ea1742-e544-4dfb-890b-bd94a9ec406e", migrated.activeServicePresetId)
        assertTrue(migrated.servicePresets.any { it.id == migrated.activeServicePresetId })
    }

    @Test
    fun `a real install gains hotkeys added after it was saved`() {
        // A saved configuration carries the hotkey list as it was, so a new action reaches
        // existing users only through a migration step. Neither of these files has ever heard of
        // SHOW_IMAGES; both must come out of the migration with it bound.
        listOf(V3_INSTALL, V2_INSTALL).forEach { payload ->
            val actions = load(payload).hotkeys.map { it.action }
            assertTrue(HotkeyAction.SHOW_IMAGES in actions, "SHOW_IMAGES missing after migration")
            assertEquals(actions.distinct().size, actions.size, "a hotkey was bound twice")
        }
    }

    @Test
    fun `a real payload does not fall back to defaults`() {
        // The failure this whole class exists for: deserialization throwing, the repository
        // swallowing it, and the user silently receiving a factory configuration.
        //
        // The preset id is what proves it, not the service selections: an install that never
        // moved off Google migrates to exactly the default selections, and comparing those would
        // pass for a real config and for a wiped one alike. The id is generated per install.
        listOf(V3_INSTALL, V2_INSTALL).forEach { payload ->
            val migrated = load(payload)
            assertNotEquals(Configuration.DEFAULT.activeServicePresetId, migrated.activeServicePresetId)
            assertEquals("__default__", migrated.servicePresets.single().name)
        }
    }

    private companion object {

        /** `%APPDATA%`-style install, config version 3, AI translator selected. */
        val V3_INSTALL = """
            {"configVersion":3,"servicePresets":[{"id":"72ea1742-e544-4dfb-890b-bd94a9ec406e",
            "name":"__default__","selectedServices":{"TRANSLATOR":"ai-translator","TTS":"google-tts",
            "SPELL_CHECKER":"google-spell-checker","OCR":"google-ocr","DICTIONARY":"google-dictionary"}}],
            "activeServicePresetId":"72ea1742-e544-4dfb-890b-bd94a9ec406e","preferredTargetLanguage":"ar",
            "preferredSourceLanguage":"en","mainWindowSize":{"width":686,"height":544},
            "mainWindowPosition":{"x":1030,"y":530},"uiScale":125}
        """.trimIndent().replace("\n", "")

        /**
         * The maintainer's own 1.3.0 profile, taken from `datastore/app_settings.preferences_pb`
         * before the 1.4.0 release.
         *
         * Kept because it is the release-blocking case in one file: it is at version 3, its five
         * services all use the pre-v2 id format, its interface is Arabic, and it runs at 125%.
         * A migration bug here loses a real person's settings, and it loses them silently.
         *
         * A custom theme id is included deliberately — `custom:` prefixed themes come from a file
         * on disk, and a migration that mangled the id would leave the app on the default theme
         * with no explanation.
         */
        val V3_REAL_PROFILE = """
            {"configVersion":3,"servicePresets":[{"id":"72ea1742-e544-4dfb-890b-bd94a9ec406e",
            "name":"__default__","selectedServices":{"TRANSLATOR":"deepl-services-translator",
            "TTS":"google-tts","SPELL_CHECKER":"google-spell-checker","OCR":"google-ocr",
            "DICTIONARY":"google-dictionary"}}],
            "activeServicePresetId":"72ea1742-e544-4dfb-890b-bd94a9ec406e",
            "interfaceLanguage":"ar-SA","preferredTargetLanguage":"ru","preferredSourceLanguage":"en",
            "mainWindowSize":{"width":686,"height":559},"mainWindowPosition":{"x":1077,"y":464},
            "uiScale":125,"themeId":"custom:qtranslate_light"}
        """.trimIndent().replace("\n", "")

        /** An older install left at config version 2, still on Google Translate. */
        val V2_INSTALL = """
            {"configVersion":2,"servicePresets":[{"id":"a66e1cb7-b2a6-4885-ba93-960950919991",
            "name":"__default__","selectedServices":{"TRANSLATOR":"google-translator","TTS":"google-tts",
            "SPELL_CHECKER":"google-spell-checker","OCR":"google-ocr","DICTIONARY":"google-dictionary"}}],
            "activeServicePresetId":"a66e1cb7-b2a6-4885-ba93-960950919991",
            "mainWindowSize":{"width":500,"height":536},"mainWindowPosition":{"x":960,"y":608}}
        """.trimIndent().replace("\n", "")
    }
}

package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the downgrade-safe persistence contract for the Batch-3 writing selection actions.
 *
 * Writing rows live under `writingHotkeys` — never under the legacy `hotkeys` list — so an older
 * QTranslate build that cannot decode [HotkeyAction.REPLACE_WITH_REWRITE] / `_SUMMARY` still reads
 * the legacy section without hitting an unknown enum value.
 */
class WritingConfigurationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Persisted configurations include default-valued fields, so the serialized sections are
        // always present and downgrade-readability can be asserted on the real payload shape.
        encodeDefaults = true
    }

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    @Test
    fun `writing actions ship unassigned in their own bucket`() {
        assertEquals(
            setOf(HotkeyAction.REPLACE_WITH_REWRITE, HotkeyAction.REPLACE_WITH_SUMMARY),
            HotkeyBinding.WRITING_ACTIONS,
        )
        val defaults = Configuration.DEFAULT.writingHotkeys.associateBy { it.action }
        assertEquals(HotkeyBinding.WRITING_ACTIONS, defaults.keys)
        assertTrue(defaults.values.all { !it.hasBinding }, "no writing key is taken from the user by default")
        assertTrue(
            Configuration.DEFAULT.hotkeys.none { it.action in HotkeyBinding.WRITING_ACTIONS },
            "writing actions must never sit in the legacy hotkeys bucket",
        )
    }

    @Test
    fun `v9 to v10 migration publishes both writing rows without touching legacy hotkeys`() {
        val legacyBefore = Configuration.DEFAULT.hotkeys.map { it.copy() }
        val v9 = Configuration.DEFAULT.copy(
            configVersion = 9,
            hotkeys = legacyBefore,
            writingHotkeys = emptyList(),
        )

        val migrated = ConfigMigrator.migrate(v9, logger)

        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.configVersion)
        assertEquals(
            HotkeyBinding.WRITING_ACTIONS,
            migrated.writingHotkeys.map { it.action }.toSet(),
            "both writing rows are published",
        )
        assertTrue(migrated.writingHotkeys.all { !it.hasBinding })
        assertEquals(legacyBefore, migrated.hotkeys, "legacy hotkeys stay verbatim")
        assertTrue(migrated.hotkeys.none { it.action in HotkeyBinding.WRITING_ACTIONS })
    }

    @Test
    fun `v9 to v10 migration is idempotent`() {
        val v9 = Configuration.DEFAULT.copy(
            configVersion = 9,
            writingHotkeys = emptyList(),
        )
        val once = ConfigMigrator.migrate(v9, logger)
        val twice = ConfigMigrator.migrate(once, logger)
        assertEquals(once, twice)
        assertEquals(once.writingHotkeys, twice.writingHotkeys)
    }

    @Test
    fun `migration preserves an existing custom writing binding`() {
        val custom = HotkeyBinding(
            action = HotkeyAction.REPLACE_WITH_REWRITE,
            keyCode = java.awt.event.KeyEvent.VK_R,
            modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.ALT_DOWN_MASK,
            scope = HotkeyScope.LOCAL,
        )
        val v9 = Configuration.DEFAULT.copy(
            configVersion = 9,
            writingHotkeys = listOf(custom),
        )

        val migrated = ConfigMigrator.migrate(v9, logger)

        assertEquals(custom, migrated.writingHotkeys.single { it.action == custom.action })
        assertEquals(
            setOf(HotkeyAction.REPLACE_WITH_SUMMARY),
            migrated.writingHotkeys.filter { it.action != custom.action }.map { it.action }.toSet(),
        )
    }

    @Test
    fun `a custom writing binding survives serialize and reload`() {
        val custom = HotkeyBinding(
            action = HotkeyAction.REPLACE_WITH_SUMMARY,
            keyCode = java.awt.event.KeyEvent.VK_S,
            modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,
            scope = HotkeyScope.LOCAL,
        )
        val configuration = Configuration.DEFAULT.copy(
            configVersion = ConfigMigrator.CURRENT_VERSION,
            writingHotkeys = Configuration.DEFAULT.writingHotkeys
                .filterNot { it.action == custom.action } + custom,
        )

        val encoded = json.encodeToString(Configuration.serializer(), configuration)
        val reloaded = json.decodeFromString<Configuration>(encoded)

        assertEquals(custom, reloaded.writingHotkeys.single { it.action == custom.action })
        assertEquals(
            HotkeyScope.LOCAL,
            reloaded.effectiveHotkeys.single { it.action == custom.action }.scope,
        )
    }

    @Test
    fun `serialized legacy hotkeys section contains no writing vocabulary`() {
        val migrated = ConfigMigrator.migrate(
            Configuration.DEFAULT.copy(configVersion = 9, writingHotkeys = emptyList()),
            logger,
        )
        val encoded = json.encodeToString(Configuration.serializer(), migrated)
        val legacyPayload = encoded.substringAfter("\"hotkeys\":").substringBefore("\"ocrHotkeys\"")

        assertTrue("\"REPLACE_WITH_REWRITE\"" !in legacyPayload, "legacy hotkeys must stay downgrade-readable")
        assertTrue("\"REPLACE_WITH_SUMMARY\"" !in legacyPayload, "legacy hotkeys must stay downgrade-readable")
        // The writing rows are present in their own bucket, so persistence is not lost.
        assertTrue("\"REPLACE_WITH_REWRITE\"" in encoded.substringAfter("\"writingHotkeys\":"))
        assertTrue("\"REPLACE_WITH_SUMMARY\"" in encoded.substringAfter("\"writingHotkeys\":"))
    }

    @Test
    fun `an older configuration without writingHotkeys decodes to the safe default`() {
        val raw = """{"configVersion":9,"hotkeys":[]}"""
        val decoded = json.decodeFromString<Configuration>(raw)

        assertEquals(HotkeyBinding.WRITING_DEFAULTS, decoded.writingHotkeys)
        assertTrue(decoded.writingHotkeys.all { !it.hasBinding })
        assertFalse(decoded.hotkeys.any { it.action in HotkeyBinding.WRITING_ACTIONS })

        val migrated = ConfigMigrator.migrate(decoded, logger)
        assertEquals(HotkeyBinding.WRITING_DEFAULTS, migrated.writingHotkeys)
        assertEquals(decoded.hotkeys, migrated.hotkeys)
    }
}

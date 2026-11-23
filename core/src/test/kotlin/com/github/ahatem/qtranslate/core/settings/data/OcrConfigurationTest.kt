package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import com.github.ahatem.qtranslate.core.ocr.ocrWorkflowCommand
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrConfigurationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /** A v7 configuration: an explicit hotkey list without the OCR rows and a customised OCR key. */
    private fun v7ConfigWithCustomOpenOcrKey(): Configuration {
        val oldActions = HotkeyBinding.DEFAULTS
            .filterNot { it.action in NEW_OCR_ACTIONS }
            .map { binding ->
                if (binding.action == HotkeyAction.OPEN_OCR) {
                    binding.copy(keyCode = java.awt.event.KeyEvent.VK_F9, modifiers = 0)
                } else {
                    binding.copy()
                }
            }
        return Configuration.DEFAULT.copy(configVersion = 7, hotkeys = oldActions, ocrHotkeys = emptyList())
    }

    @Test
    fun `v7 explicit hotkey lists receive every new OCR action on migration`() {
        val v7 = v7ConfigWithCustomOpenOcrKey()

        val migrated = ConfigMigrator.migrate(v7, logger)

        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.configVersion)
        // OCR rows live under ocrHotkeys so a v7 binary never sees them.
        val actions = migrated.effectiveHotkeys.map { it.action }.toSet()
        assertTrue(actions.containsAll(NEW_OCR_ACTIONS), "old explicit lists still receive the new rows")
        assertTrue(migrated.hotkeys.none { it.action in NEW_OCR_ACTIONS }, "legacy list stays v7-readable")
        assertTrue(migrated.ocrHotkeys.map { it.action }.toSet().containsAll(NEW_OCR_ACTIONS), "OCR rows persist separately")
        assertEquals(
            java.awt.event.KeyEvent.VK_F9,
            migrated.hotkeys.single { it.action == HotkeyAction.OPEN_OCR }.keyCode,
            "migration must append missing rows, not replace the user's bindings",
        )
        assertEquals(migrated, ConfigMigrator.migrate(migrated, logger), "the v7 step is idempotent")
    }

    @Test
    fun `migrated serialization keeps the legacy list downgrade-readable`() {
        val v7 = v7ConfigWithCustomOpenOcrKey()
        val migrated = ConfigMigrator.migrate(v7, logger)
        val encoded = json.encodeToString(Configuration.serializer(), migrated)

        // The pre-OCR schema must not see OCR vocabulary inside `hotkeys`:
        // a v7 decoder dies on any unknown enum value before the migrator runs.
        assertTrue("\"OCR_" !in encoded.substringAfter("\"hotkeys\":").substringBefore("\"ocrHotkeys\""),
            "legacy hotkeys payload must contain no OCR actions for a v7 reader")
        assertTrue("\"CAPTURE\"" !in encoded.substringAfter("\"hotkeys\":").substringBefore("\"ocrHotkeys\""),
            "legacy hotkeys payload must contain no CAPTURE scope for a v7 reader")
        // Legacy customization survives verbatim, OCR rows persist alongside.
        assertEquals(
            java.awt.event.KeyEvent.VK_F9,
            migrated.hotkeys.single { it.action == HotkeyAction.OPEN_OCR }.keyCode,
        )
        assertTrue(migrated.ocrHotkeys.map { it.action }.toSet().containsAll(NEW_OCR_ACTIONS))
        // Reload with the current vocabulary: both halves present and idempotent.
        val reloaded = json.decodeFromString<Configuration>(encoded)
        assertEquals(migrated, ConfigMigrator.migrate(reloaded, logger))
    }

    @Test
    fun `every global OCR hotkey maps to its distinct workflow command`() {
        assertEquals(OcrWorkflowCommand.DEFAULT, HotkeyAction.OPEN_OCR.ocrWorkflowCommand())
        assertEquals(OcrWorkflowCommand.SHOW_ACTIONS, HotkeyAction.OCR_SHOW_ACTIONS.ocrWorkflowCommand())
        assertEquals(OcrWorkflowCommand.TRANSLATE, HotkeyAction.OCR_TRANSLATE.ocrWorkflowCommand())
        assertEquals(OcrWorkflowCommand.TRANSLATE_QUICK, HotkeyAction.OCR_TRANSLATE_QUICK.ocrWorkflowCommand())
        assertEquals(OcrWorkflowCommand.TRANSLATE_MAIN, HotkeyAction.OCR_TRANSLATE_MAIN.ocrWorkflowCommand())
        assertEquals(OcrWorkflowCommand.OPEN_WORKSPACE, HotkeyAction.OCR_OPEN_WORKSPACE.ocrWorkflowCommand())
        assertEquals(OcrWorkflowCommand.COPY_TEXT, HotkeyAction.OCR_COPY_TEXT.ocrWorkflowCommand())
        assertEquals(OcrWorkflowCommand.COPY_IMAGE, HotkeyAction.OCR_COPY_IMAGE.ocrWorkflowCommand())
        assertEquals(null, HotkeyAction.OCR_ACTION_CANCEL.ocrWorkflowCommand())
    }

    private companion object {
        val CAPTURE_ACTIONS = setOf(
            HotkeyAction.OCR_ACTION_TRANSLATE,
            HotkeyAction.OCR_ACTION_COPY_TEXT,
            HotkeyAction.OCR_ACTION_COPY_IMAGE,
            HotkeyAction.OCR_ACTION_SAVE_IMAGE,
            HotkeyAction.OCR_ACTION_RECROP,
            HotkeyAction.OCR_ACTION_CANCEL,
        )
        val NEW_OCR_ACTIONS = setOf(
            HotkeyAction.OCR_SHOW_ACTIONS,
            HotkeyAction.OCR_TRANSLATE,
            HotkeyAction.OCR_TRANSLATE_QUICK,
            HotkeyAction.OCR_TRANSLATE_MAIN,
            HotkeyAction.OCR_OPEN_WORKSPACE,
            HotkeyAction.OCR_COPY_TEXT,
            HotkeyAction.OCR_COPY_IMAGE,
        ) + CAPTURE_ACTIONS
    }
}

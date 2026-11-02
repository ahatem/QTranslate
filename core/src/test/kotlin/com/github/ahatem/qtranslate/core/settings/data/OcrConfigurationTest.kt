package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import com.github.ahatem.qtranslate.core.ocr.ocrWorkflowCommand
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrConfigurationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    @Test
    fun `the workflow default keeps existing users on the action toolbar`() {
        assertEquals(OcrPostSelectionAction.SHOW_ACTIONS, OcrSettings().postSelectionAction)
        assertEquals(OcrTranslationDestination.MAIN_WINDOW, OcrSettings().translationDestination)
        assertFalse(OcrSettings().removeLineBreaks)
        assertTrue(OcrSettings().showWorkspaceAnnotations)
        assertEquals("", OcrSettings().recognitionLanguage)
    }

    @Test
    fun `every post-selection action and destination round trips`() {
        OcrPostSelectionAction.entries.forEach { action ->
            OcrTranslationDestination.entries.forEach { destination ->
                val settings = OcrSettings(
                    postSelectionAction = action,
                    translationDestination = destination,
                    recognitionLanguage = "ja",
                    removeLineBreaks = true,
                    showWorkspaceAnnotations = false,
                )
                val decoded = json.decodeFromString<Configuration>(
                    json.encodeToString(Configuration.DEFAULT.copy(ocr = settings)),
                )
                assertEquals(settings, decoded.ocr)
            }
        }
    }

    @Test
    fun `an older configuration without OCR fields decodes to the compatible default`() {
        val raw = """{"configVersion":7,"hotkeys":[]}"""
        val decoded = json.decodeFromString<Configuration>(raw)
        assertEquals(OcrSettings(), decoded.ocr)
        assertEquals(OcrPostSelectionAction.SHOW_ACTIONS, decoded.ocr.postSelectionAction)
    }

    @Test
    fun `v7 explicit hotkey lists receive every new OCR action on migration`() {
        val oldActions = HotkeyBinding.DEFAULTS
            .filterNot { it.action in NEW_OCR_ACTIONS }
            .map { binding ->
                if (binding.action == HotkeyAction.OPEN_OCR) {
                    binding.copy(keyCode = java.awt.event.KeyEvent.VK_F9, modifiers = 0)
                } else {
                    binding.copy()
                }
            }
        val v7 = Configuration.DEFAULT.copy(
            configVersion = 7,
            hotkeys = oldActions,
        )

        val migrated = ConfigMigrator.migrate(v7, logger)

        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.configVersion)
        val actions = migrated.hotkeys.map { it.action }.toSet()
        assertTrue(actions.containsAll(NEW_OCR_ACTIONS), "old explicit lists still receive the new rows")
        assertEquals(
            java.awt.event.KeyEvent.VK_F9,
            migrated.hotkeys.single { it.action == HotkeyAction.OPEN_OCR }.keyCode,
            "migration must append missing rows, not replace the user's bindings",
        )
        assertEquals(migrated, ConfigMigrator.migrate(migrated, logger), "the v7 step is idempotent")
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

    @Test
    fun `specialized OCR workflows ship unassigned and capture actions use their own scope`() {
        val bindings = HotkeyBinding.DEFAULTS.associateBy { it.action }

        (NEW_OCR_ACTIONS - CAPTURE_ACTIONS).forEach { action ->
            assertFalse(bindings.getValue(action).hasBinding, "$action must not claim a key by default")
        }
        CAPTURE_ACTIONS.forEach { action ->
            assertEquals(HotkeyScope.CAPTURE, bindings.getValue(action).scope)
            assertFalse(bindings.getValue(action).hasBinding)
        }
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

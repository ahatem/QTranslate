package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotkeyBindingTest {

    @Test
    fun `toDisplayString formats modifiers with default labels`() {
        val binding = HotkeyBinding(
            action = HotkeyAction.OCR_COPY_TEXT,
            keyCode = KeyEvent.VK_C,
            modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
            scope = HotkeyScope.LOCAL
        )
        assertEquals("Ctrl+Shift+C", binding.toDisplayString())
        assertEquals(listOf("Ctrl", "Shift", "C"), binding.toTokens())
    }

    @Test
    fun `toDisplayString formats modifiers with localized labels`() {
        val spanishLabels = ModifierLabels(
            ctrl = "Ctrl",
            alt = "Alt",
            shift = "Mayús",
            meta = "⌘"
        )
        val binding = HotkeyBinding(
            action = HotkeyAction.OCR_COPY_TEXT,
            keyCode = KeyEvent.VK_C,
            modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
            scope = HotkeyScope.LOCAL
        )
        assertEquals("Ctrl+Mayús+C", binding.toDisplayString(spanishLabels))
        assertEquals(listOf("Ctrl", "Mayús", "C"), binding.toTokens(spanishLabels))
    }

    @Test
    fun `unassigned binding returns empty display string and tokens`() {
        val binding = HotkeyBinding(
            action = HotkeyAction.OCR_TRANSLATE,
            keyCode = 0,
            modifiers = 0,
            scope = HotkeyScope.LOCAL
        )
        assertFalse(binding.hasBinding)
        assertEquals("", binding.toDisplayString())
        assertTrue(binding.toTokens().isEmpty())
    }

    @Test
    fun `ocr actions have local scope and unassigned keys by default`() {
        val ocrDefaults = HotkeyBinding.DEFAULTS.filter { it.action.name.startsWith("OCR_") }
        assertEquals(6, ocrDefaults.size)
        ocrDefaults.forEach { binding ->
            assertEquals(HotkeyScope.LOCAL, binding.scope)
            assertEquals(0, binding.keyCode)
            assertEquals(0, binding.modifiers)
            assertFalse(binding.hasBinding)
        }
    }

    @Test
    fun `round-trip serialization of hotkey bindings with OCR actions`() {
        val json = Json { ignoreUnknownKeys = true }
        val bindings = listOf(
            HotkeyBinding(HotkeyAction.OCR_TRANSLATE, KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OCR_COPY_TEXT, KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OCR_CANCEL, KeyEvent.VK_ESCAPE, 0, scope = HotkeyScope.LOCAL)
        )

        bindings.forEach { original ->
            val encoded = json.encodeToString(HotkeyBinding.serializer(), original)
            val decoded = json.decodeFromString(HotkeyBinding.serializer(), encoded)
            assertEquals(original, decoded)
        }
    }
}

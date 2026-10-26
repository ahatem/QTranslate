package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.data.ModifierLabels
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScopedOcrShortcutsTest {

    @Test
    fun `button tooltips show shortcut only when configured and null when unassigned`() {
        val hotkeys = listOf(
            HotkeyBinding(HotkeyAction.OCR_TRANSLATE, KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OCR_COPY_TEXT, 0, 0, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OCR_COPY_IMAGE, 0, 0, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OCR_SAVE_IMAGE, KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OCR_RECROP, 0, 0, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OCR_CANCEL, KeyEvent.VK_ESCAPE, 0, scope = HotkeyScope.LOCAL)
        )

        val labels = ModifierLabels(ctrl = "Ctrl", alt = "Alt", shift = "Shift", meta = "⌘")
        fun shortcutFor(action: HotkeyAction): String? =
            hotkeys.find { it.action == action && it.isEnabled }
                ?.toDisplayString(labels)
                ?.takeIf { it.isNotEmpty() }

        val tooltips = ScreenCapturePanel.ButtonTooltips(
            translate = shortcutFor(HotkeyAction.OCR_TRANSLATE),
            copyText = shortcutFor(HotkeyAction.OCR_COPY_TEXT),
            copyImage = shortcutFor(HotkeyAction.OCR_COPY_IMAGE),
            saveImage = shortcutFor(HotkeyAction.OCR_SAVE_IMAGE),
            recrop = shortcutFor(HotkeyAction.OCR_RECROP),
            cancel = shortcutFor(HotkeyAction.OCR_CANCEL)
        )

        assertEquals("Ctrl+T", tooltips.translate)
        assertNull(tooltips.copyText, "Unassigned action has null tooltip")
        assertNull(tooltips.copyImage, "Unassigned action has null tooltip")
        assertEquals("Ctrl+S", tooltips.saveImage)
        assertNull(tooltips.recrop, "Unassigned action has null tooltip")
        assertEquals("Escape", tooltips.cancel)
    }

    @Test
    fun `button tooltips format with localized modifier labels`() {
        val hotkeys = listOf(
            HotkeyBinding(HotkeyAction.OCR_COPY_TEXT, KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL)
        )
        val spanishLabels = ModifierLabels(ctrl = "Ctrl", alt = "Alt", shift = "Mayús", meta = "⌘")
        val shortcut = hotkeys.first().toDisplayString(spanishLabels)

        assertEquals("Ctrl+Mayús+C", shortcut)
    }

    @Test
    fun `ScreenCapturePanel buttons receive tooltips from ButtonTooltips`() {
        val tooltips = ScreenCapturePanel.ButtonTooltips(
            translate = "Ctrl+Enter",
            copyText = null,
            copyImage = "Ctrl+Shift+C",
            saveImage = null,
            recrop = "Ctrl+R",
            cancel = "Escape"
        )

        val panel = ScreenCapturePanel(
            onTranslate = {},
            onCopyText = {},
            onCopyImage = {},
            onSaveImage = {},
            onRecrop = {},
            onCancel = {},
            buttonTooltips = tooltips
        )

        fun findButtons(container: java.awt.Container): List<JButton> =
            container.components.flatMap { comp ->
                when (comp) {
                    is JButton -> listOf(comp)
                    is java.awt.Container -> findButtons(comp)
                    else -> emptyList()
                }
            }

        val buttons = findButtons(panel)

        val translateBtn = buttons.firstOrNull { it.text == "Translate" }
        val copyTextBtn = buttons.firstOrNull { it.text == "Copy Text" }
        val recropBtn = buttons.firstOrNull { it.text == "Re-crop" }

        assertEquals("Ctrl+Enter", translateBtn?.toolTipText)
        assertNull(copyTextBtn?.toolTipText)
        assertEquals("Ctrl+R", recropBtn?.toolTipText)
    }
}

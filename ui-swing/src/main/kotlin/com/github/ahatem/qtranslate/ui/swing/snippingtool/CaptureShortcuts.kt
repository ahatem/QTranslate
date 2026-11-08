package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import java.awt.event.KeyEvent

/**
 * The capture-local shortcut projection.
 *
 * Only [HotkeyScope.CAPTURE] bindings belong here. Global OCR workflows and the main window's
 * LOCAL shortcuts deliberately do not: all three sets have different lifetimes, and installing
 * one in another surface is how a shortcut starts swallowing keys it was never meant to own.
 */
internal fun captureShortcutBindings(bindings: List<HotkeyBinding>): Map<HotkeyAction, HotkeyBinding> =
    bindings
        .filter { it.scope == HotkeyScope.CAPTURE && it.isEnabled && it.hasBinding }
        .associateBy { it.action }

/**
 * True when [action] is a capture-toolbar action and [keyCode]/[modifiers] is the bare
 * Enter or Escape the overlay reserves for its primary/cancel actions.
 * Settings must reject this at recording time; the runtime installer drops it too,
 * so the UI must never present it as saved successfully.
 */
internal fun isReservedBareCaptureKey(action: HotkeyAction, keyCode: Int, modifiers: Int): Boolean =
    action.captureToolbarAction() != null &&
        modifiers == 0 &&
        (keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_ESCAPE)

/** The capture shortcuts that can actually be installed without stealing Enter or Escape. */
internal fun installableCaptureShortcuts(
    bindings: List<HotkeyBinding>,
): List<Pair<HotkeyBinding, CaptureToolbarAction>> =
    captureShortcutBindings(bindings)
        .values
        .mapNotNull { binding ->
            val toolbar = binding.action.captureToolbarAction() ?: return@mapNotNull null
            val keyCode = binding.toKeyStroke()?.keyCode ?: return@mapNotNull null
            // Bare Enter and Escape are the capture dialog's primary and cancel actions.
            // Explicit combinations such as Ctrl+Enter remain available as toolbar shortcuts.
            if (binding.modifiers == 0 &&
                (keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_ESCAPE)
            ) {
                return@mapNotNull null
            }
            binding to toolbar
        }

/** The toolbar action a capture shortcut represents, or null for a global workflow action. */
internal fun HotkeyAction.captureToolbarAction(): CaptureToolbarAction? = when (this) {
    HotkeyAction.OCR_ACTION_TRANSLATE -> CaptureToolbarAction.TRANSLATE
    HotkeyAction.OCR_ACTION_COPY_TEXT -> CaptureToolbarAction.COPY_TEXT
    HotkeyAction.OCR_ACTION_COPY_IMAGE -> CaptureToolbarAction.COPY_IMAGE
    HotkeyAction.OCR_ACTION_SAVE_IMAGE -> CaptureToolbarAction.SAVE_IMAGE
    HotkeyAction.OCR_ACTION_RECROP -> CaptureToolbarAction.RECROP
    HotkeyAction.OCR_ACTION_CANCEL -> CaptureToolbarAction.CANCEL
    else -> null
}

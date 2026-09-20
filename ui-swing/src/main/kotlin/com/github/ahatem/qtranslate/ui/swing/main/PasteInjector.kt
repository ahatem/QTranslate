package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.TriggerNeutralizer
import com.github.ahatem.qtranslate.ui.swing.main.input.permitsFallback
import io.github.ahatem.qinput.QInputException
import io.github.ahatem.qinput.QInputKey
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent

/**
 * Writes translated text to the clipboard and injects the platform paste chord exactly once.
 *
 * Reuses the generic `sendChord` primitive (no paste-specific native API) plus the same
 * bounded neutralization philosophy as selection capture: before injecting, the paste key
 * and every modifier that would transform the chord must read physically up. The chord's
 * own modifier is exempt by the same ownership argument as Copy (holding Ctrl still yields
 * exactly Ctrl+V). User-held keys are never synthesized up/down.
 *
 * Chords and watched keys are expressed as portable QInput usages (`QInputKey.*`), so the same
 * code drives the Windows, macOS and X11 chord once a backend advertises injection.
 *
 * Order is neutralize → clipboard write → inject, back-to-back: this minimizes the window
 * in which unrelated activity can interleave, and a neutralization timeout leaves the
 * clipboard untouched (unlike the old write-first order).
 *
 * Product semantics preserved: translated text intentionally remains on the clipboard;
 * nothing is restored afterwards. Failures log metadata only, never text.
 */
internal interface PasteInjector {

    /**
     * Writes [text] and injects paste exactly once. Returns true when a paste was
     * dispatched; false means nothing was sent (caller logs, no retry after uncertain
     * delivery).
     */
    suspend fun injectPaste(text: String): Boolean
}

/** Native path with Robot fallback, per the capability/fallback policy. */
internal class QInputPasteInjector(
    private val backend: () -> GlobalInputBackend?,
    private val clipboardWrite: (String) -> Unit = { text ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    },
    private val logger: Logger,
    private val fallback: suspend (String) -> Boolean = { text -> RobotPasteInjector(logger).injectPaste(text) },
    private val isMac: Boolean = currentPlatformIsMac(),
) : PasteInjector {

    override suspend fun injectPaste(text: String): Boolean {
        val target = backend()
        if (target == null || !target.supportsInjection) return fallback(text)

        // Neutralization needs a physical key-state query. Where the backend has none the wait
        // could only ever time out, so it is skipped rather than turning every paste into a
        // two-second stall followed by a failure; the chord's own modifier is still preserved
        // by the injector's ownership rule. Backends that offer no query are exactly the ones
        // whose injection path is not advertised today.
        if (target.capabilities.keyState) {
            val neutralizer = TriggerNeutralizer(anyDown = { usages ->
                runCatching { target.anyKeyDown(usages) }.getOrDefault(true)
            })
            if (!neutralizer.awaitNeutralUsages(pasteWatchedSet(isMac))) {
                logger.warn("Paste modifiers never released; paste not dispatched")
                return false
            }
        }

        val (modifiers, key) = platformPasteChord(isMac)
        clipboardWrite(text)
        return try {
            target.sendChord(modifiers, key)
        } catch (e: QInputException) {
            // A failure that proves zero delivery is safe to retry through Robot. Anything else
            // — in particular a Windows partial SendInput — leaves delivery uncertain: fail
            // closed instead of risking a duplicate paste.
            if (e.permitsFallback()) fallback(text)
            else {
                logger.warn("Native paste injection outcome uncertain; not retrying: ${e.message}")
                false
            }
        } catch (e: Exception) {
            logger.warn("Native paste injection outcome uncertain; not retrying: ${e.message}")
            false
        }
    }

    companion object {
        fun currentPlatformIsMac(): Boolean =
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

        /** Platform paste chord as portable usages: Ctrl+V, or Cmd+V on macOS. */
        fun platformPasteChord(isMac: Boolean): Pair<List<Int>, Int> =
            if (isMac) listOf(QInputKey.MOD_LEFT_META) to QInputKey.V
            else listOf(QInputKey.MOD_LEFT_CONTROL) to QInputKey.V

        /**
         * Keys that must read up before paste: the paste key plus every contaminating modifier
         * except the chord's own. Held Ctrl still yields exactly Ctrl+V via ownership
         * preservation; held Shift/Alt/Meta would transform the chord.
         *
         * <p>Both sides of every modifier are watched. macOS and X11 distinguish left and right,
         * so a physically held right Shift/Alt/Super would otherwise slip through and
         * contaminate the paste. The chord's own modifier is exempted by <em>family</em>: a held
         * right Ctrl is as harmless as a held left Ctrl for the same reason.
         */
        fun pasteWatchedSet(isMac: Boolean): List<Int> {
            val (chordMods, key) = platformPasteChord(isMac)
            val exempt = chordMods.toSet()
            val contaminants = QInputKey.ALL_MODIFIERS.filterNot { usage ->
                exempt.any { QInputKey.isSameModifierFamily(it, usage) }
            }
            return (listOf(key) + contaminants).distinct()
        }
    }
}

/** Legacy Robot paste with correct per-platform modifier mapping. */
internal class RobotPasteInjector(
    private val logger: Logger,
    private val driverFactory: () -> RobotKeyDriver = { AwtRobotKeyDriver() },
) : PasteInjector {

    override suspend fun injectPaste(text: String): Boolean {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        val driver = try {
            driverFactory()
        } catch (e: Exception) {
            logger.warn("Robot paste failed: could not create a Robot")
            return false
        }
        val pasteModifier = if (QInputPasteInjector.currentPlatformIsMac()) {
            KeyEvent.VK_META
        } else {
            KeyEvent.VK_CONTROL
        }
        var modifierPressed = false
        var keyPressed = false
        return try {
            driver.keyPress(pasteModifier)
            modifierPressed = true
            driver.keyPress(KeyEvent.VK_V)
            keyPressed = true
            driver.keyRelease(KeyEvent.VK_V)
            keyPressed = false
            driver.keyRelease(pasteModifier)
            modifierPressed = false
            driver.waitForIdle()
            true
        } catch (e: Exception) {
            logger.warn("Robot paste failed")
            // Reverse press order, and only release keys this Robot itself pressed and did not
            // already release — never a key it never touched, and never twice.
            if (keyPressed) runCatching { driver.keyRelease(KeyEvent.VK_V) }
            if (modifierPressed) runCatching { driver.keyRelease(pasteModifier) }
            false
        }
    }
}

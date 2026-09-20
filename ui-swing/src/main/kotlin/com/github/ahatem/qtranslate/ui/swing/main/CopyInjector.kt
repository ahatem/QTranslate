package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.permitsFallback
import io.github.ahatem.qinput.QInputException
import io.github.ahatem.qinput.QInputKey
import java.awt.event.KeyEvent

/**
 * Synthesizes the Copy chord for selection capture.
 *
 * QInput native injection is preferred where the backend offers it; the AWT Robot stays as
 * the fallback for platforms without the capability. Either way the clipboard
 * generation/change observation remains the actual completion signal, never sleeps.
 *
 * **Exactly-one-attempt contract.** A [CopyInjector] owns its *complete* attempt, fallback
 * included: the caller ([MainGlobalKeyListener.simulateCopy]) invokes whichever [CopyInjector] is
 * currently configured exactly once and does not itself retry on failure. [QInputCopyInjector]
 * is the implementation that has an internal fallback (to Robot, by default); a bare
 * [RobotCopyInjector] configured directly (as the pre-native-availability default is) has none,
 * so its own failure is simply terminal — a second, unrelated `RobotCopyInjector` must never be
 * constructed to "retry" it.
 *
 * **What the return value means.** [injectCopy] returning `true` does not mean "Copy was
 * confirmed delivered" — it means "this call believes no further action should be taken", which
 * covers three different underlying outcomes: genuine native success, an internal Robot fallback
 * that itself reported success, and a delivery-uncertain native outcome that must not be retried
 * regardless of whether it actually landed. `false` means the opposite: nothing usable happened
 * and it would have been safe to try something else, but there was nothing else configured to
 * try. Either way, the actual completion signal for selection capture remains clipboard
 * generation/change observation downstream, never this return value.
 */
internal fun interface CopyInjector {

    /** Performs the complete Copy attempt, fallback included. See the interface doc for what the
     * return value does and does not promise. */
    fun injectCopy(): Boolean
}

/** Legacy path: AWT Robot Ctrl/Meta+C. Owns no further fallback; a failure here is terminal. */
internal class RobotCopyInjector(
    private val logger: Logger,
    private val driverFactory: () -> RobotKeyDriver = { AwtRobotKeyDriver() },
) : CopyInjector {

    override fun injectCopy(): Boolean {
        val driver = try {
            driverFactory()
        } catch (e: Exception) {
            logger.warn("Copy simulation failed: could not create a Robot")
            return false
        }
        val copyModifier = if (QInputCopyInjector.currentPlatformIsMac()) {
            KeyEvent.VK_META
        } else {
            KeyEvent.VK_CONTROL
        }
        var modifierPressed = false
        var keyPressed = false
        return try {
            driver.keyPress(copyModifier)
            modifierPressed = true
            driver.keyPress(KeyEvent.VK_C)
            keyPressed = true
            driver.keyRelease(KeyEvent.VK_C)
            keyPressed = false
            driver.keyRelease(copyModifier)
            modifierPressed = false
            driver.waitForIdle()
            true
        } catch (e: Exception) {
            logger.warn("Copy simulation failed")
            // Reverse press order, and only release keys this Robot itself pressed and did not
            // already release — never a key it never touched, and never twice.
            if (keyPressed) runCatching { driver.keyRelease(KeyEvent.VK_C) }
            if (modifierPressed) runCatching { driver.keyRelease(copyModifier) }
            false
        }
    }
}

/**
 * Native path: one injected chord through QInput, using the portable key namespace.
 * The modifier is the platform's clipboard modifier (Cmd on macOS, Ctrl elsewhere), expressed
 * as a portable modifier usage rather than a Win32 virtual key.
 *
 * Owns its own fallback (mirrors [QInputPasteInjector]): Robot runs only when native delivery
 * definitely never began (no backend, unsupported capability, or a validation/not-delivered
 * failure that proves zero delivery — see [com.github.ahatem.qtranslate.ui.swing.main.input.NativeInjectionStatus]).
 * When delivery is uncertain — in particular a Windows partial `SendInput`, or any failure whose
 * delivery state cannot be proven zero — this returns `false` from the *native* attempt without
 * invoking [fallback] at all, and the whole call reports `true` (nothing further to do). Owning
 * the fallback internally, rather than leaving the caller to retry on a bare `false`, is what
 * keeps this to exactly one logical Copy attempt: [MainGlobalKeyListener.simulateCopy] calls
 * [injectCopy] once and never itself constructs a second injector.
 */
internal class QInputCopyInjector(
    private val backend: () -> GlobalInputBackend?,
    private val logger: Logger,
    private val fallback: () -> Boolean = { RobotCopyInjector(logger).injectCopy() },
    private val isMac: Boolean = currentPlatformIsMac(),
) : CopyInjector {

    override fun injectCopy(): Boolean {
        val target = backend() ?: return fallback()
        if (!target.supportsInjection) return fallback()
        val (modifiers, key) = copyChord(isMac)
        return try {
            val accepted = target.sendChord(modifiers, key)
            if (accepted) true else fallback()
        } catch (e: QInputException) {
            if (e.permitsFallback()) {
                logger.warn("Native copy injection not delivered, falling back to Robot: ${e.message}")
                fallback()
            } else {
                logger.warn("Native copy injection outcome uncertain; not retrying: ${e.message}")
                true
            }
        } catch (e: Exception) {
            logger.warn("Native copy injection outcome uncertain; not retrying: ${e.message}")
            true
        }
    }

    companion object {
        fun currentPlatformIsMac(): Boolean =
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

        /** Portable Copy chord: Ctrl+C, or Cmd+C on macOS. */
        fun copyChord(isMac: Boolean): Pair<List<Int>, Int> =
            if (isMac) listOf(QInputKey.MOD_LEFT_META) to QInputKey.C
            else listOf(QInputKey.MOD_LEFT_CONTROL) to QInputKey.C
    }
}

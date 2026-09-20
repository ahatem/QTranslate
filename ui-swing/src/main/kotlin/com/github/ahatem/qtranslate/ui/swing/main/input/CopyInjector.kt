package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.api.core.Logger
import io.github.ahatem.qinput.QInputException
import io.github.ahatem.qinput.QInputKey
import java.awt.event.KeyEvent

/**
 * Synthesizes the Copy chord for selection capture; the clipboard change observation is the
 * real completion signal, never this call's return value.
 *
 * The caller invokes the configured injector exactly once and never retries; each injector owns
 * its complete attempt including any fallback. `true` means no further action should be taken
 * (success, a successful fallback, or an uncertain outcome that must not be retried); `false`
 * means nothing usable happened and nothing else was configured to try.
 */
internal fun interface CopyInjector {
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
            // Only release keys this attempt itself pressed, and never twice.
            if (keyPressed) runCatching { driver.keyRelease(KeyEvent.VK_C) }
            if (modifierPressed) runCatching { driver.keyRelease(copyModifier) }
            false
        }
    }
}

/**
 * Native path: one injected chord through QInput. Owns its own fallback: Robot runs only when
 * [NativeInjectionStatus.permitsFallback] proves native delivery never began. On an uncertain
 * outcome this returns `true` without invoking [fallback], since retrying blind could duplicate
 * an already-delivered chord.
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

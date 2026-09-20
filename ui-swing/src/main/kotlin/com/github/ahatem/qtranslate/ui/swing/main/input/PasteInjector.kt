package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.api.core.Logger
import io.github.ahatem.qinput.QInputException
import io.github.ahatem.qinput.QInputKey
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent

/**
 * Writes translated text to the clipboard and injects the platform paste chord exactly once.
 *
 * Order is neutralize → clipboard write → inject, back-to-back, so a neutralization timeout
 * leaves the clipboard untouched. The clipboard intentionally keeps the translated text
 * afterwards; failures log metadata only, never text.
 */
internal interface PasteInjector {

    /** Returns true when a paste was dispatched; false means nothing was sent. */
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

        // Skipped when the backend has no key-state query, since the wait could only time out.
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
            // Only a failure that proves zero delivery is safe to retry; otherwise fail closed.
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
         * Keys that must read up before paste: the paste key plus every contaminating modifier,
         * excluding the chord's own modifier family. Both sides of each modifier are watched,
         * since macOS and X11 distinguish left and right.
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
    private val clipboardWrite: (String) -> Unit = { text ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    },
) : PasteInjector {

    override suspend fun injectPaste(text: String): Boolean {
        clipboardWrite(text)
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
            // Only release keys this attempt itself pressed, and never twice.
            if (keyPressed) runCatching { driver.keyRelease(KeyEvent.VK_V) }
            if (modifierPressed) runCatching { driver.keyRelease(pasteModifier) }
            false
        }
    }
}

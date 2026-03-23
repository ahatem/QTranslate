package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.tulskiy.keymaster.common.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean

class MainGlobalKeyListener(
    private val scope: CoroutineScope,
    private val onShowApp: (String) -> Unit,
    private val onShowQuickTranslate: (String) -> Unit,
    private val onListenToText: (String) -> Unit,
    private val onOpenSnippingTool: () -> Unit
) {

    private var provider: Provider? = null
    private var nativeHookRegistered = false
    private val sequenceListener = CustomSequenceListener()
    private val clipboardLock = AtomicBoolean(false)
    private val hotkeysEnabled = AtomicBoolean(true)
    private var initialized = false

    @Volatile private var bindings: List<HotkeyBinding> = HotkeyBinding.DEFAULTS

    fun initialize() {
        if (initialized) return
        try {
            initJKeyMaster()
            initJNativeHook()
            initialized = true
        } catch (e: Exception) {
            System.err.println("Hotkey initialization failed: ${e.message}")
            e.printStackTrace()
        }
    }

    fun updateBindings(newBindings: List<HotkeyBinding>) {
        bindings = newBindings
        if (!initialized) return
        try {
            provider?.reset()
            registerHotkeys()
        } catch (e: Exception) {
            System.err.println("Failed to update hotkey bindings: ${e.message}")
        }
    }

    fun setHotkeysEnabled(enabled: Boolean) {
        if (!initialized) return
        if (hotkeysEnabled.getAndSet(enabled) == enabled) return
        if (enabled) enableHotkeys() else disableHotkeys()
    }

    fun areHotkeysEnabled(): Boolean = hotkeysEnabled.get()

    fun shutdown() {
        if (!initialized) return
        try {
            provider?.reset()
            provider?.stop()
            provider = null

            if (nativeHookRegistered) {
                GlobalScreen.removeNativeKeyListener(sequenceListener)
                GlobalScreen.unregisterNativeHook()
                nativeHookRegistered = false
            }
        } catch (e: Exception) {
            System.err.println("Hotkey manager shutdown error: ${e.message}")
        } finally {
            initialized = false
        }
    }

    private fun initJKeyMaster() {
        provider = Provider.getCurrentProvider(false)
            ?: throw Exception("Hotkey provider unavailable")
        registerHotkeys()
    }

    private fun registerHotkeys() {
        val p = provider ?: return

        bindings.filter { it.isEnabled && it.hasBinding }
            .forEach { binding ->
                val keyStroke = binding.toKeyStroke() ?: return@forEach
                val action = binding.action
                p.register(keyStroke) {
                    if (!hotkeysEnabled.get()) return@register
                    when (action) {
                        HotkeyAction.SHOW_QUICK_TRANSLATE ->
                            scope.launch { handleSelectedText(onShowQuickTranslate) }
                        HotkeyAction.LISTEN_TO_TEXT ->
                            scope.launch { handleSelectedText(onListenToText) }
                        HotkeyAction.OPEN_OCR ->
                            onOpenSnippingTool()
                        HotkeyAction.SHOW_MAIN_WINDOW ->
                            scope.launch { handleSelectedText(onShowApp) }
                    }
                }
            }
    }

    private fun enableHotkeys() {
        try {
            provider?.reset()
            registerHotkeys()
        } catch (e: Exception) {
            System.err.println("Enable hotkeys failed: ${e.message}")
        }
    }

    private fun disableHotkeys() {
        try {
            provider?.reset()
        } catch (e: Exception) {
            System.err.println("Disable hotkeys failed: ${e.message}")
        }
    }

    private fun initJNativeHook() {
        try {
            if (!nativeHookRegistered) {
                GlobalScreen.registerNativeHook()
                nativeHookRegistered = true
            }
            GlobalScreen.addNativeKeyListener(sequenceListener)
        } catch (ex: NativeHookException) {
            throw Exception("Native hook registration failed", ex)
        }
    }

    // The double-Ctrl sequence for SHOW_MAIN_WINDOW is handled by JNativeHook
    // since it requires detecting two rapid key presses — not expressible as a
    // single KeyStroke. It is always active regardless of the binding's keystroke.
    private inner class CustomSequenceListener : NativeKeyListener {
        private var lastCtrlTime = 0L
        private val threshold = 400

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            if (!hotkeysEnabled.get()) return
            if (e.keyCode != NativeKeyEvent.VC_CONTROL) return

            // Check if the SHOW_MAIN_WINDOW action is enabled in bindings
            val binding = bindings.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            if (binding?.isEnabled == false) return

            val now = System.currentTimeMillis()
            if (now - lastCtrlTime < threshold) {
                scope.launch { handleSelectedText(onShowApp) }
            }
            lastCtrlTime = now
        }
    }

    private suspend fun handleSelectedText(callback: (String) -> Unit) {
        if (!clipboardLock.compareAndSet(false, true)) return

        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val original = runCatching { clipboard.getContents(null) }.getOrNull()
            val originalText = original?.let {
                runCatching { it.getTransferData(DataFlavor.stringFlavor).toString() }.getOrNull()
            }

            var text: String? = null

            repeat(2) {
                simulateCopy()
                delay(50)
                text = runCatching {
                    if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                        clipboard.getData(DataFlavor.stringFlavor).toString().trim()
                    } else null
                }.getOrNull()

                if (!text.isNullOrEmpty()) return@repeat
            }

            if (text.isNullOrEmpty()) text = originalText ?: ""
            callback(text)
            original?.let { runCatching { clipboard.setContents(it, null) } }
        } finally {
            clipboardLock.set(false)
        }
    }

    private fun simulateCopy() {
        runCatching {
            val robot = Robot()
            robot.autoDelay = 20
            robot.keyPress(KeyEvent.VK_CONTROL)
            robot.keyPress(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_C)
            robot.keyRelease(KeyEvent.VK_CONTROL)
        }.onFailure {
            System.err.println("Copy simulation failed: ${it.message}")
        }
    }
}
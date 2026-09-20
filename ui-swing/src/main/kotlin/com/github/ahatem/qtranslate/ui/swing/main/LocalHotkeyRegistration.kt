package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JRootPane

/**
 * Installs LOCAL-scope hotkeys on the window's root pane InputMap/ActionMap.
 *
 * The action map retains the actual [HotkeyBinding] for every trigger, so the binding — not just
 * its [HotkeyAction] — reaches [dispatch]. Selection-dependent actions need it: without the
 * binding there is nothing to wait on for deterministic trigger neutralization, and a
 * locally-triggered Ctrl+Shift+&lt;key&gt; is still physically held when the Swing action fires.
 *
 * Actions the frame itself owns (focus moves, dialogs, clipboard) route to [directHandlers] and
 * stay immediate; everything else is handed to [dispatch], which applies the same scope-neutral
 * dispatch decision the global hotkey path uses.
 */
internal class LocalHotkeyRegistration(
    private val rootPane: JRootPane,
    private val bindings: () -> List<HotkeyBinding>,
    private val directHandlers: Map<HotkeyAction, () -> Unit>,
    private val dispatch: (HotkeyBinding) -> Unit,
) {

    /**
     * Rebuilds the InputMap/ActionMap from the current LOCAL bindings.
     *
     * WHEN_ANCESTOR_OF_FOCUSED_COMPONENT fires whenever any descendant has focus, which is always
     * the case (text pane, buttons, etc.); WHEN_FOCUSED would only fire if the root pane itself
     * held focus — which never happens.
     */
    fun register() {
        val inputMap = rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.clear()
        rootPane.actionMap.clear()

        bindings().forEach { binding ->
            val keyStroke = binding.toKeyStroke() ?: return@forEach
            val actionKey = "localHotkey_${binding.action.name}"
            inputMap.put(keyStroke, actionKey)
            rootPane.actionMap.put(actionKey, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val direct = directHandlers[binding.action]
                    if (direct != null) direct() else dispatch(binding)
                }
            })
        }
    }
}

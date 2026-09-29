package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JRootPane

/**
 * Installs LOCAL-scope hotkeys on the window's root pane InputMap/ActionMap.
 *
 * The action map stores the full [HotkeyBinding], not just [HotkeyAction]: selection-dependent
 * actions need it to wait for deterministic trigger neutralization, since a locally-triggered
 * Ctrl+Shift+&lt;key&gt; is still physically held when the Swing action fires.
 */
internal class LocalHotkeyRegistration(
    private val rootPane: JRootPane,
    private val bindings: () -> List<HotkeyBinding>,
    private val directHandlers: Map<HotkeyAction, () -> Unit>,
    private val dispatch: (HotkeyBinding) -> Unit,
) {

    /**
     * WHEN_ANCESTOR_OF_FOCUSED_COMPONENT fires whenever any descendant has focus, which is
     * always true here; WHEN_FOCUSED never fires since the root pane itself never holds focus.
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

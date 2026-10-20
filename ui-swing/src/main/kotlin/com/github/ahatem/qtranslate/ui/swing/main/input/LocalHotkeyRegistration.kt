package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.ActionMap
import javax.swing.InputMap
import javax.swing.JComponent
import javax.swing.JRootPane

/**
 * Installs LOCAL-scope hotkeys on the window's root pane InputMap/ActionMap.
 *
 * The action map stores the full [HotkeyBinding], not just [HotkeyAction]: selection-dependent
 * actions need it to wait for deterministic trigger neutralization, since a locally-triggered
 * Ctrl+Shift+&lt;key&gt; is still physically held when the Swing action fires.
 *
 * Each Swing InputMap condition has its own map, so the rows this registration owns are the ones
 * it puts in the ancestor map. The ActionMap is the one shared surface: every other feature
 * installed on that window keeps its action there — the main window's Escape-to-hide action among
 * them — so this registration owns only the action entries in its own key namespace.
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
        val actionMap = rootPane.actionMap
        removeOwnedBindings(inputMap, actionMap)

        bindings().forEach { binding ->
            val keyStroke = binding.toKeyStroke() ?: return@forEach
            val actionKey = actionKeyFor(binding.action)
            inputMap.put(keyStroke, actionKey)
            actionMap.put(actionKey, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    val direct = directHandlers[binding.action]
                    if (direct != null) direct() else dispatch(binding)
                }
            })
        }
    }

    /**
     * Removes only the entries this registration owns.
     *
     * Each InputMap condition has its own map, so the rows owned here are the ones this
     * registration put in the ancestor map. The ActionMap is the shared surface: every other
     * feature installed on the window keeps its action there, the main window's Escape-to-hide
     * action among them. Clearing it wholesale would silently delete another feature's action,
     * leaving its keystroke bound to an action that no longer exists, so only the action entries
     * this registration created are ever removed.
     *
     * The owned set is exact and derived from [HotkeyAction], so an unbound or rebound hotkey
     * leaves no stale row or action behind, while an action key that merely resembles an owned one
     * is left alone.
     */
    private fun removeOwnedBindings(inputMap: InputMap, actionMap: ActionMap) {
        inputMap.keys().orEmpty()
            .filter { isOwnedActionKey(inputMap.get(it)) }
            .forEach { inputMap.remove(it) }
        actionMap.keys().orEmpty()
            .filter { isOwnedActionKey(it) }
            .forEach { actionMap.remove(it) }
    }

    /** True when [actionKey] names an action this registration owns. */
    private fun isOwnedActionKey(actionKey: Any?): Boolean =
        actionKey is String && actionKey in OWNED_ACTION_KEYS

    internal companion object {
        /**
         * Prefix of the action keys this registration owns.
         *
         * The prefix is the key *format*; [OWNED_ACTION_KEYS] is the exact set of keys this
         * registration owns, and only those may ever be removed or replaced.
         */
        const val ACTION_KEY_PREFIX = "localHotkey_"

        /** The action-map key this registration owns for [action]. */
        fun actionKeyFor(action: HotkeyAction): String = "$ACTION_KEY_PREFIX${action.name}"

        /**
         * Every action key this registration owns: one per [HotkeyAction].
         *
         * Derived from the enum so the set is exact — an action key that merely starts with
         * [ACTION_KEY_PREFIX] is not owned and is never removed — and so every key this
         * registration creates is in the set, which keeps a re-registration from leaving a stale
         * row or action behind.
         */
        val OWNED_ACTION_KEYS: Set<String> =
            HotkeyAction.entries.mapTo(LinkedHashSet()) { actionKeyFor(it) }
    }
}

package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration-style test of the real LOCAL Swing routing that MainAppFrame installs.
 *
 * It drives the actual InputMap/ActionMap built by [LocalHotkeyRegistration], resolving a
 * keystroke and firing the action exactly as Swing would, rather than only testing a private
 * helper. The load-bearing property is that a selection-dependent LOCAL trigger reaches
 * `dispatch` carrying its exact [HotkeyBinding], because that is what the neutralized capture
 * path needs in order to wait on the right trigger keys.
 */
class LocalHotkeyRegistrationTest {

    private fun local(action: HotkeyAction, keyCode: Int, modifiers: Int) =
        HotkeyBinding(action, keyCode = keyCode, modifiers = modifiers, scope = HotkeyScope.LOCAL)

    private fun selectionBinding() = local(
        HotkeyAction.SHOW_IMAGES, KeyEvent.VK_Q,
        InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
    )

    /** Fires a binding through Swing exactly as the platform would for the installed InputMap. */
    private fun fire(rootPane: JRootPane, binding: HotkeyBinding) {
        val inputMap = rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionKey = inputMap.get(binding.toKeyStroke())
            ?: error("no InputMap entry for ${binding.action}")
        val action = rootPane.actionMap.get(actionKey)
            ?: error("no ActionMap action for $actionKey")
        action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "test"))
    }

    @Test
    fun `selection-dependent local trigger dispatches with its exact binding`() {
        val rootPane = JRootPane()
        val binding = selectionBinding()
        val dispatched = mutableListOf<HotkeyBinding>()

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(binding) },
            directHandlers = emptyMap(),
            dispatch = { dispatched += it },
        ).register()

        fire(rootPane, binding)

        assertEquals(listOf(binding), dispatched, "the actual binding must reach the dispatcher")
        // The modifier/key detail survives, so neutralization can watch the real trigger keys.
        assertEquals(KeyEvent.VK_Q, dispatched.single().keyCode)
        assertEquals(
            InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
            dispatched.single().modifiers
        )
        assertEquals(HotkeyScope.LOCAL, dispatched.single().scope)
    }

    @Test
    fun `frame-owned local action uses its direct handler and not the dispatcher`() {
        val rootPane = JRootPane()
        val binding = local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)
        var focusCalls = 0
        var dispatched = 0

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(binding) },
            directHandlers = mapOf(HotkeyAction.FOCUS_INPUT to { focusCalls++ }),
            dispatch = { dispatched++ },
        ).register()

        fire(rootPane, binding)

        assertEquals(1, focusCalls)
        assertEquals(0, dispatched, "frame-owned actions must not go through the capture dispatcher")
    }

    @Test
    fun `local non-selection action is routed to the scope-aware dispatcher`() {
        val rootPane = JRootPane()
        val binding = local(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)
        val dispatched = mutableListOf<HotkeyBinding>()

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(binding) },
            directHandlers = emptyMap(),
            dispatch = { dispatched += it },
        ).register()

        fire(rootPane, binding)

        // The listener's dispatcher then handles it immediately (no capture, no wait).
        assertEquals(listOf(binding), dispatched)
    }

    @Test
    fun `re-registering replaces the previous local set`() {
        val rootPane = JRootPane()
        var bindings = listOf(selectionBinding())
        val dispatched = mutableListOf<HotkeyBinding>()
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { bindings },
            directHandlers = emptyMap(),
            dispatch = { dispatched += it },
        )

        registration.register()
        fire(rootPane, selectionBinding())
        assertEquals(1, dispatched.size)

        val replacement = local(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK)
        bindings = listOf(replacement)
        registration.register()

        val inputMap = rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        assertNull(
            inputMap.get(selectionBinding().toKeyStroke()),
            "cleared bindings must not remain registered"
        )
        fire(rootPane, replacement)
        assertEquals(2, dispatched.size)
        assertEquals(replacement, dispatched.last())
    }

    @Test
    fun `unbound local actions install nothing`() {
        val rootPane = JRootPane()
        val unbound = HotkeyBinding(HotkeyAction.SHOW_HISTORY, keyCode = 0, scope = HotkeyScope.LOCAL)
        var dispatched = 0

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(unbound) },
            directHandlers = emptyMap(),
            dispatch = { dispatched++ },
        ).register()

        assertEquals(0, rootPane.actionMap.size())
        assertEquals(0, dispatched)
    }
}

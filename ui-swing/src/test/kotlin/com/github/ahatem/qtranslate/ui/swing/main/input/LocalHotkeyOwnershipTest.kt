package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.ui.swing.main.MainWindowEscapeBinding
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ownership contract of the LOCAL hotkey registration on the main window's root pane (#216).
 *
 * Each Swing InputMap condition has its own map, so the rows this registration owns are the ones
 * it puts in the ancestor map. The ActionMap is the one shared surface: every other feature
 * installed on that window keeps its action there — the main window's Escape-to-hide action among
 * them. The registration may therefore remove or replace only what it owns, never the whole map.
 *
 * The regression this pins down: the registration used to clear the root pane's ActionMap, so
 * every later re-registration (the window being opened, a settings change) silently deleted the
 * Escape action that [MainWindowEscapeBinding] had installed. The keystroke stayed bound to an
 * action that no longer existed, which is why Escape stopped hiding the main window in 1.5.0.
 *
 * Deterministic and headless: real InputMap/ActionMap lookups, no Robot and no timing.
 */
class LocalHotkeyOwnershipTest {

    private fun local(action: HotkeyAction, keyCode: Int, modifiers: Int) =
        HotkeyBinding(action, keyCode = keyCode, modifiers = modifiers, scope = HotkeyScope.LOCAL)

    /** The row another feature put in the same root pane map this registration also uses. */
    private val foreignKeyStroke: KeyStroke =
        KeyStroke.getKeyStroke(KeyEvent.VK_F9, InputEvent.ALT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK)

    private val foreignActionKey = "some-other-feature-action"

    /** Installs an entry owned by a different feature on the same root pane maps. */
    private fun installForeignEntry(rootPane: JRootPane, onFire: () -> Unit) {
        rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(foreignKeyStroke, foreignActionKey)
        rootPane.actionMap.put(foreignActionKey, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onFire()
        })
    }

    /** The action Swing would resolve for the root pane's Escape row, or null when it is gone. */
    private fun resolveEscapeAction(rootPane: JRootPane): Action? {
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionKey = inputMap.get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE) ?: return null
        return rootPane.actionMap.get(actionKey)
    }

    /** Fires the installed Escape action exactly as Swing would for the InputMap entry. */
    private fun fireEscape(rootPane: JRootPane) {
        val action = resolveEscapeAction(rootPane)
            ?: error("no ActionMap action for the main window's Escape binding")
        action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "test"))
    }

    /** The action-map keys this registration owns. */
    private fun ownedActionKeys(rootPane: JRootPane): List<String> =
        rootPane.actionMap.keys().orEmpty()
            .filter { it is String && it.startsWith(LocalHotkeyRegistration.ACTION_KEY_PREFIX) }
            .map { it as String }
            .sorted()

    private fun ancestorInputMap(rootPane: JRootPane) =
        rootPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)

    @Test
    fun `an entry owned by another feature survives local hotkey registration`() {
        val rootPane = JRootPane()
        var foreignFires = 0
        installForeignEntry(rootPane) { foreignFires++ }

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)) },
            directHandlers = emptyMap(),
            dispatch = {},
        ).register()

        assertEquals(
            foreignActionKey,
            ancestorInputMap(rootPane).get(foreignKeyStroke),
            "another feature's row in the shared map must survive the registration",
        )
        val foreignAction = rootPane.actionMap.get(foreignActionKey)
        assertNotNull(foreignAction, "another feature's action must survive the registration")
        foreignAction.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "test"))
        assertEquals(1, foreignFires, "the surviving action must still work")
    }

    @Test
    fun `re-registering replaces only the local hotkey entries`() {
        val rootPane = JRootPane()
        var foreignFires = 0
        installForeignEntry(rootPane) { foreignFires++ }
        var bindings = listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK))
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { bindings },
            directHandlers = emptyMap(),
            dispatch = {},
        )

        registration.register()
        val replacement = local(HotkeyAction.FOCUS_OUTPUT, KeyEvent.VK_2, InputEvent.ALT_DOWN_MASK)
        bindings = listOf(replacement)
        registration.register()

        assertNull(
            ancestorInputMap(rootPane).get(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK).toKeyStroke()),
            "the replaced local binding must not remain registered",
        )
        assertEquals(
            LocalHotkeyRegistration.actionKeyFor(HotkeyAction.FOCUS_OUTPUT),
            ancestorInputMap(rootPane).get(replacement.toKeyStroke()),
            "the new local binding must be registered",
        )
        assertEquals(
            listOf(LocalHotkeyRegistration.actionKeyFor(HotkeyAction.FOCUS_OUTPUT)),
            ownedActionKeys(rootPane),
            "only the current local actions may exist",
        )
        assertEquals(
            foreignActionKey,
            ancestorInputMap(rootPane).get(foreignKeyStroke),
            "another feature's row must survive a re-registration",
        )
        assertNotNull(
            rootPane.actionMap.get(foreignActionKey),
            "another feature's action must survive a re-registration",
        )
    }

    @Test
    fun `main window escape still hides after the local hotkeys are registered`() {
        // Escape installed first, then the local hotkeys: the order that matters when a settings
        // change re-registers them long after startup.
        val rootPane = JRootPane()
        var hidden = 0
        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)) },
            directHandlers = emptyMap(),
            dispatch = {},
        ).register()

        fireEscape(rootPane)

        assertEquals(1, hidden, "Escape must still hide the main window")
    }

    @Test
    fun `escape survives the frame's local escape local registration order`() {
        // The frame's own order: the local hotkeys are registered while the frame is built
        // (MainAppFrame.setupWindowListeners -> initializeGlobalHotkeys -> registerLocalHotkeys),
        // the Escape binding is installed next, and the window then opens, which registers the
        // local hotkeys once more (MainAppFrame.windowOpened -> initializeGlobalHotkeys). That
        // last registration is the one that killed Escape in the baseline.
        val rootPane = JRootPane()
        var hidden = 0
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)) },
            directHandlers = emptyMap(),
            dispatch = {},
        )

        registration.register()
        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()
        registration.register()

        fireEscape(rootPane)

        assertEquals(1, hidden, "Escape must still hide the main window")
    }

    @Test
    fun `the escape row lives in its own map and is untouched by registration`() {
        val rootPane = JRootPane()
        MainWindowEscapeBinding(rootPane = rootPane).register()

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)) },
            directHandlers = emptyMap(),
            dispatch = {},
        ).register()

        assertEquals(
            MainWindowEscapeBinding.ACTION_KEY,
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE),
            "the Escape row must survive in the focused-window map",
        )
        assertNull(
            ancestorInputMap(rootPane).get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE),
            "the ancestor map is a different map and must not carry the Escape row",
        )
    }

    @Test
    fun `an action key that only resembles an owned one survives registration`() {
        val rootPane = JRootPane()
        val resemblingKey = "x-${LocalHotkeyRegistration.ACTION_KEY_PREFIX}FOCUS_INPUT"
        rootPane.actionMap.put(resemblingKey, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = Unit
        })

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)) },
            directHandlers = emptyMap(),
            dispatch = {},
        ).register()

        assertNotNull(
            rootPane.actionMap.get(resemblingKey),
            "an action key that merely resembles an owned one is not owned and must survive",
        )
    }

    @Test
    fun `a row pointing at an owned action key is removed with it`() {
        // A row that points into this registration's namespace exists only to invoke its action,
        // so it is owned and goes away with the action it points at. Pinned so the behaviour
        // cannot change silently.
        val rootPane = JRootPane()
        ancestorInputMap(rootPane).put(
            foreignKeyStroke,
            LocalHotkeyRegistration.actionKeyFor(HotkeyAction.FOCUS_INPUT),
        )

        LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)) },
            directHandlers = emptyMap(),
            dispatch = {},
        ).register()

        assertNull(
            ancestorInputMap(rootPane).get(foreignKeyStroke),
            "a row pointing at an owned action key is owned and must be removed",
        )
        assertNotNull(
            rootPane.actionMap.get(LocalHotkeyRegistration.actionKeyFor(HotkeyAction.FOCUS_INPUT)),
            "the owned action itself must be installed by the registration",
        )
    }

    @Test
    fun `main window escape still hides after the local hotkeys are re-registered`() {
        // A settings change re-registers the local hotkeys long after startup.
        val rootPane = JRootPane()
        var hidden = 0
        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)) },
            directHandlers = emptyMap(),
            dispatch = {},
        )

        registration.register()
        registration.register()

        fireEscape(rootPane)

        assertEquals(1, hidden, "Escape must still hide the main window after a re-registration")
    }

    @Test
    fun `a shortcut change still installs the new binding`() {
        val rootPane = JRootPane()
        var bindings = listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK))
        val dispatched = mutableListOf<HotkeyBinding>()
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { bindings },
            directHandlers = emptyMap(),
            dispatch = { dispatched += it },
        )

        registration.register()
        val changed = local(HotkeyAction.CLEAR_INPUT, KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK)
        bindings = listOf(changed)
        registration.register()

        val actionKey = ancestorInputMap(rootPane).get(changed.toKeyStroke())
        assertNotNull(actionKey, "the changed shortcut must be registered")
        val action = rootPane.actionMap.get(actionKey)
        assertNotNull(action, "the changed shortcut must resolve to an action")
        action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "test"))

        assertEquals(listOf(changed), dispatched, "the changed shortcut must still fire")
    }

    @Test
    fun `repeated re-registrations leave no stale local hotkey actions`() {
        val rootPane = JRootPane()
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)) },
            directHandlers = emptyMap(),
            dispatch = {},
        )

        registration.register()
        registration.register()
        registration.register()

        assertEquals(
            listOf(LocalHotkeyRegistration.actionKeyFor(HotkeyAction.FOCUS_INPUT)),
            ownedActionKeys(rootPane),
            "repeated re-registrations must not accumulate stale actions",
        )
        assertEquals(
            listOf(local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK).toKeyStroke()),
            ancestorInputMap(rootPane).keys().orEmpty().toList(),
            "the ancestor map must hold exactly the configured row, with nothing stale left behind",
        )
        assertTrue(
            rootPane.actionMap.keys().orEmpty()
                .all { it is String && it.startsWith(LocalHotkeyRegistration.ACTION_KEY_PREFIX) },
            "only owned action keys may be installed",
        )
    }

    @Test
    fun `unbinding a hotkey removes its row and its action`() {
        val rootPane = JRootPane()
        val bound = local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)
        var bindings = listOf(bound)
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { bindings },
            directHandlers = emptyMap(),
            dispatch = {},
        )

        registration.register()
        bindings = listOf(bound.copy(keyCode = 0))
        registration.register()

        assertNull(
            ancestorInputMap(rootPane).get(bound.toKeyStroke()),
            "an unbound hotkey must leave no row behind",
        )
        assertNull(
            rootPane.actionMap.get(LocalHotkeyRegistration.actionKeyFor(HotkeyAction.FOCUS_INPUT)),
            "an unbound hotkey must leave no action behind",
        )
        assertEquals(emptyList(), ownedActionKeys(rootPane))
    }

    @Test
    fun `a local binding on the escape key leaves the hide action intact`() {
        // A user may bind a LOCAL action to Escape. The window's hide action is owned by another
        // feature, so registering and unbinding that binding must leave it installed and working.
        val rootPane = JRootPane()
        var hidden = 0
        var dispatched = 0
        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()
        val onEscape = local(HotkeyAction.CLEAR_INPUT, KeyEvent.VK_ESCAPE, 0)
        var bindings = listOf(onEscape)
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { bindings },
            directHandlers = emptyMap(),
            dispatch = { dispatched++ },
        )

        registration.register()

        assertNotNull(
            resolveEscapeAction(rootPane),
            "the hide action must survive a local binding on the same keystroke",
        )
        val localActionKey = ancestorInputMap(rootPane).get(onEscape.toKeyStroke())
        assertNotNull(localActionKey, "the local binding must be registered")
        val localAction = rootPane.actionMap.get(localActionKey)
        assertNotNull(localAction, "the local binding must resolve to an action")
        localAction.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "test"))
        assertEquals(1, dispatched, "the local binding must still fire")

        bindings = listOf(onEscape.copy(keyCode = 0))
        registration.register()

        assertNotNull(
            resolveEscapeAction(rootPane),
            "the hide action must survive unbinding the local binding",
        )
        fireEscape(rootPane)
        assertEquals(1, hidden, "Escape must hide the main window once the local binding is gone")
    }

    @Test
    fun `two actions sharing one keystroke leave no orphan actions`() {
        val rootPane = JRootPane()
        val first = local(HotkeyAction.FOCUS_INPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)
        val second = local(HotkeyAction.FOCUS_OUTPUT, KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK)
        val registration = LocalHotkeyRegistration(
            rootPane = rootPane,
            bindings = { listOf(first, second) },
            directHandlers = emptyMap(),
            dispatch = {},
        )

        registration.register()
        registration.register()

        assertEquals(
            listOf(
                LocalHotkeyRegistration.actionKeyFor(HotkeyAction.FOCUS_INPUT),
                LocalHotkeyRegistration.actionKeyFor(HotkeyAction.FOCUS_OUTPUT),
            ),
            ownedActionKeys(rootPane),
            "both actions are installed and a re-registration must not leave an orphan",
        )
        assertEquals(
            listOf(first.toKeyStroke()),
            ancestorInputMap(rootPane).keys().orEmpty().toList(),
            "the shared keystroke must hold exactly one row",
        )
    }
}

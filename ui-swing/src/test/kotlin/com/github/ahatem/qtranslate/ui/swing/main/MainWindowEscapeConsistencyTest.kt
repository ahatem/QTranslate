package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.ui.swing.main.output.ExtraOutputPanel
import com.github.ahatem.qtranslate.ui.swing.main.output.OutputTextPanel
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import java.awt.event.ActionEvent
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import kotlin.jvm.functions.Function0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Follow-up to #265 for #216: a single Escape must hide the main window no
 * matter which normal text pane has focus.
 *
 * #265 installed [MainWindowEscapeBinding] on the root pane
 * (`WHEN_IN_FOCUSED_WINDOW`), but the output/extra-output panes kept a
 * `WHEN_FOCUSED` `escape-to-input` binding that won over it, so the first
 * press from those panes only moved focus to input. Those bindings are gone:
 * every pane below is the shared [AdvancedTextPane] base the input, output,
 * and extra-output panels are built from, and none of them may shadow Escape.
 *
 * Deterministic and headless: real `InputMap`/`ActionMap` lookups plus the
 * binding seam, no Robot, no timing.
 */
class MainWindowEscapeConsistencyTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** The text surface every main-window pane is built from. */
    private fun newPane(): AdvancedTextPane = onEdt {
        AdvancedTextPane(
            onTextChanged = {},
            onTranslateRequest = {},
            onListenRequest = {},
        )
    }

    /** Fires the installed Escape action exactly as Swing would for the InputMap entry. */
    private fun fireEscape(rootPane: JRootPane) {
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionKey = inputMap.get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE)
            ?: error("no WHEN_IN_FOCUSED_WINDOW entry for Escape")
        val action = rootPane.actionMap.get(actionKey)
            ?: error("no ActionMap action for $actionKey")
        action.actionPerformed(ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, "test"))
    }

    /**
     * A pane shadows the window binding only through a `WHEN_FOCUSED` Escape
     * entry, which is what the removed `escape-to-input` binding was. Without
     * one, Swing falls through to the root pane's `WHEN_IN_FOCUSED_WINDOW`
     * entry — the central hide behavior.
     */
    private fun assertPaneDoesNotShadowEscape(pane: AdvancedTextPane) {
        assertNull(
            pane.getInputMap(JComponent.WHEN_FOCUSED).get(MainWindowEscapeBinding.ESCAPE_KEYSTROKE),
            "no WHEN_FOCUSED Escape entry may shadow the main-window binding",
        )
        assertNull(
            pane.actionMap.get("escape-to-input"),
            "no escape-to-input action may remain on the pane",
        )
    }

    @Test
    fun `escape from input pane hides the main window`() {
        val rootPane = JRootPane()
        val pane = newPane()
        onEdt { rootPane.contentPane.add(pane) }
        var hidden = 0

        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()
        assertPaneDoesNotShadowEscape(pane)

        fireEscape(rootPane)

        assertEquals(1, hidden, "Escape from the input pane must hide the main window")
    }

    @Test
    fun `escape from output pane hides the main window`() {
        val rootPane = JRootPane()
        val pane = newPane()
        onEdt { rootPane.contentPane.add(pane) }
        var hidden = 0

        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()
        assertPaneDoesNotShadowEscape(pane)

        fireEscape(rootPane)

        assertEquals(1, hidden, "Escape from the output pane must hide the main window in one press")
    }

    @Test
    fun `escape from extra-output pane hides the main window`() {
        val rootPane = JRootPane()
        val pane = newPane()
        onEdt { rootPane.contentPane.add(pane) }
        var hidden = 0

        MainWindowEscapeBinding(rootPane = rootPane, onHide = { hidden++ }).register()
        assertPaneDoesNotShadowEscape(pane)

        fireEscape(rootPane)

        assertEquals(1, hidden, "Escape from the extra-output pane must hide the main window in one press")
    }

    @Test
    fun `no escape-to-input callback plumbing remains on the output panels`() {
        listOf(OutputTextPanel::class.java, ExtraOutputPanel::class.java).forEach { panel ->
            assertTrue(
                panel.declaredFields.none { it.type == Function0::class.java },
                "${panel.simpleName} must not keep an onEscapePressed field",
            )
            assertTrue(
                panel.declaredConstructors.none { ctor ->
                    ctor.parameterTypes.contains(Function0::class.java)
                },
                "${panel.simpleName} must not take an onEscapePressed constructor argument",
            )
            assertFalse(
                containsString(classBytes(panel), "escape-to-input"),
                "${panel.simpleName} must not reference the escape-to-input binding",
            )
        }
    }

    @Test
    fun `escape from output pane while translating still cancels instead of hiding`() {
        val rootPane = JRootPane()
        val pane = newPane()
        onEdt { rootPane.contentPane.add(pane) }
        var hidden = 0
        var cancelled = 0

        MainWindowEscapeBinding(
            rootPane = rootPane,
            isTranslationInFlight = { true },
            isChildHandlingEscape = { false },
            onCancelTranslation = { cancelled++ },
            onHide = { hidden++ },
        ).register()
        assertPaneDoesNotShadowEscape(pane)

        fireEscape(rootPane)

        assertEquals(1, cancelled, "in-flight Escape must cancel the translation first")
        assertEquals(0, hidden, "cancelling must not hide the window")
    }

    @Test
    fun `escape from extra-output pane owned by a child popup is still left alone`() {
        val rootPane = JRootPane()
        val pane = newPane()
        onEdt { rootPane.contentPane.add(pane) }
        var hidden = 0
        var cancelled = 0

        MainWindowEscapeBinding(
            rootPane = rootPane,
            isTranslationInFlight = { false },
            isChildHandlingEscape = { true },
            onCancelTranslation = { cancelled++ },
            onHide = { hidden++ },
        ).register()
        assertPaneDoesNotShadowEscape(pane)

        fireEscape(rootPane)

        assertEquals(0, hidden, "a child-owned Escape must not hide the main window")
        assertEquals(0, cancelled, "a child-owned Escape must not cancel either")
    }

    private fun classBytes(panel: Class<*>): ByteArray {
        val path = panel.name.replace('.', '/') + ".class"
        return panel.classLoader.getResourceAsStream(path)?.readBytes()
            ?: error("cannot load bytecode for ${panel.name}")
    }

    private fun containsString(bytes: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}

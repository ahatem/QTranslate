package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Component
import java.awt.Container
import java.awt.FocusTraversalPolicy
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JTextPane
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A focus cycle only answers for a displayable root, which would make these need a display and fail
 * on a headless build machine, so the order comes from a stub delegate. The delegate is the one
 * thing this policy composes with, so it is the thing worth pinning.
 */
class SkipMarkedFocusPolicyTest {

    /** A focus cycle of a fixed order, standing in for whatever the host's own policy produces. */
    private class FixedOrder(vararg val cycle: Component) : FocusTraversalPolicy() {
        private fun step(from: Component, delta: Int): Component? {
            if (cycle.size < 2) return null
            val index = cycle.indexOfFirst { it === from }
            if (index < 0) return null
            return cycle[Math.floorMod(index + delta, cycle.size)]
        }

        override fun getComponentAfter(c: Container, component: Component) = step(component, 1)
        override fun getComponentBefore(c: Container, component: Component) = step(component, -1)
        override fun getFirstComponent(c: Container) = cycle.firstOrNull()
        override fun getLastComponent(c: Container) = cycle.lastOrNull()
        override fun getDefaultComponent(c: Container) = cycle.firstOrNull()
    }

    private val container = JButton("host root").apply { isFocusCycleRoot = true }

    private fun definitionPane(text: String): JTextPane = JTextPane().apply {
        setText(text)
        isEditable = false
        isFocusable = true
        skipFocusTraversal()
    }

    private fun definitionArea(text: String): JTextArea = JTextArea(text).apply {
        isEditable = false
        isFocusable = true
        skipFocusTraversal()
    }

    private fun policyOver(vararg cycle: Component) = SkipMarkedFocusPolicy(FixedOrder(*cycle))

    @Test
    fun `unmarked components stay traversal eligible`() {
        val field = JTextField("fox")
        val plain = JTextArea("some other read-only text").apply { isFocusable = true }
        val button = JButton("Look Up")
        val policy = policyOver(field, plain, button)

        assertSame(plain, policy.getComponentAfter(container, field), "Tab reaches the text area")
        assertSame(button, policy.getComponentAfter(container, plain))
        assertSame(field, policy.getComponentBefore(container, plain), "and Shift+Tab comes back")
        assertSame(field, policy.getComponentAfter(container, button), "wrapping as the delegate does")
        assertSame(field, policy.getFirstComponent(container))
        assertSame(button, policy.getLastComponent(container))
    }

    @Test
    fun `a marked text component is skipped in both directions`() {
        val field = JTextField("fox")
        listOf(definitionPane("a quick brown fox"), definitionArea("the quick brown fox jumped"))
            .forEach { text ->
                val button = JButton("Look Up")
                val policy = policyOver(field, text, button)

                assertSame(button, policy.getComponentAfter(container, field), "Tab steps over the marked text")
                assertSame(button, policy.getComponentBefore(container, field), "and so does the wrap around")
                assertSame(field, policy.getComponentBefore(container, button), "and Shift+Tab steps back over it")
                assertSame(field, policy.getFirstComponent(container), "and it is not the first stop")
                assertSame(button, policy.getLastComponent(container), "and not the last")
            }
    }

    @Test
    fun `consecutive marked components are stepped over as one`() {
        val field = JTextField("fox")
        val button = JButton("Look Up")
        val policy = policyOver(
            field,
            definitionPane("a quick brown fox"),
            definitionArea("the quick brown fox jumped"),
            definitionPane("a sly creature"),
            button,
        )

        assertSame(button, policy.getComponentAfter(container, field), "three in a row are one gap")
        assertSame(field, policy.getComponentBefore(container, button), "crossed back over as one too")
    }

    @Test
    fun `traversal out of a focused marked component reaches a normal control`() {
        val field = JTextField("fox")
        val definition = definitionPane("a quick brown fox")
        val lookup = JButton("Look Up")
        val close = JButton("Close")
        val policy = policyOver(field, definition, lookup, close)

        assertSame(lookup, policy.getComponentAfter(container, definition), "Tab leaves the definition")
        assertSame(field, policy.getComponentBefore(container, definition), "Shift+Tab leaves it too")
        assertSame(field, policy.getComponentBefore(container, lookup), "crossing the definition")
        assertSame(close, policy.getComponentAfter(container, lookup), "and the rest of the cycle is intact")
    }

    @Test
    fun `a marked component is still focusable and still read-only`() {
        listOf(definitionPane("a quick brown fox"), definitionArea("the quick brown fox jumped"))
            .forEach { text ->
                assertTrue(text.isFocusable, "a click must be able to leave the caret in it")
                assertFalse(text.isEditable, "and it is still not written into")
                assertTrue(text.skipsFocusTraversal(), "while staying out of the Tab cycle")
                assertTrue(
                    text.getActionMap().get("copy") != null,
                    "so the standard copy action is there for the keyboard shortcut",
                )
            }
    }

    @Test
    fun `a cycle of only marked components has no destination`() {
        val first = definitionPane("a quick brown fox")
        val second = definitionArea("the quick brown fox jumped")
        val policy = policyOver(first, second)

        assertNull(policy.getComponentAfter(container, first), "nothing here is a legal Tab destination")
        assertNull(policy.getComponentBefore(container, second), "in either direction")
        assertNull(policy.getFirstComponent(container))
        assertNull(policy.getLastComponent(container))
    }

    @Test
    fun `the policy keeps the delegate's order and only drops the marked components`() {
        val field = JTextField("fox")
        val definition = definitionPane("a quick brown fox")
        val cycle: Array<Component> = arrayOf(field, definition, JButton("Look Up"), JButton("Close"))
        val delegate = FixedOrder(*cycle)

        assertSame(definition, delegate.getComponentAfter(container, field), "the delegate offers it")
        assertSame(field, delegate.getComponentAfter(container, cycle[3]), "and wraps as it always did")

        val policy = SkipMarkedFocusPolicy(delegate)
        assertSame(cycle[2], policy.getComponentAfter(container, definition), "the policy removes it")
        assertSame(cycle[3], policy.getComponentAfter(container, cycle[2]), "and leaves the rest in order")
        assertSame(field, policy.getComponentAfter(container, cycle[3]))
        assertSame(field, policy.getDefaultComponent(container), "an unmarked default is kept as it is")
    }

    @Test
    fun `a marked default component falls back to the first eligible one`() {
        val definition = definitionPane("a quick brown fox")
        val policy = policyOver(definition, JTextField("fox"))

        assertSame(
            policy.getComponentAfter(container, definition),
            policy.getDefaultComponent(container),
            "a marked default is not returned as one",
        )
    }
}
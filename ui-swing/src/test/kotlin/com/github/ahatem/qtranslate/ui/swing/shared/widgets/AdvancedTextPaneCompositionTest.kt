package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.text.AttributedString
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals

/** Synthetic IME events exercise Swing's real composition positions without an installed IME. */
class AdvancedTextPaneCompositionTest {
    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun pane(emitted: MutableList<String>): AdvancedTextPane {
        lateinit var pane: AdvancedTextPane
        onEdt {
            pane = AdvancedTextPane({ emitted += it }, {}, {})
        }
        return pane
    }

    private fun AdvancedTextPane.imeEvent(committed: String = "", composing: String = "") {
        dispatchEvent(InputMethodEvent(
            this, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            AttributedString(committed + composing).iterator, committed.length, null, null,
        ))
    }

    private fun AdvancedTextPane.loseFocus() {
        // An undisplayed test pane cannot become the native focus owner.
        val event = FocusEvent(this, FocusEvent.FOCUS_LOST)
        focusListeners.forEach { it.focusLost(event) }
    }

    @Test
    fun `a stale echo does not replace active composition or emit provisional text`() {
        val emitted = mutableListOf<String>()
        val pane = pane(emitted)
        onEdt {
            pane.imeEvent(composing = "ㄘ")
            pane.imeEvent(composing = "ㄘㄜ")
            assertEquals(emptyList(), emitted)
            pane.render("ㄘ", emptyList(), true)
            assertEquals("ㄘㄜ", pane.text)
            pane.imeEvent(committed = "測試")
            assertEquals("測試", pane.text)
            assertEquals(listOf("測試"), emitted)
            pane.render("replacement", emptyList(), true)
            assertEquals("replacement", pane.text)
        }
    }

    @Test
    fun `ordinary editing still emits text`() {
        val emitted = mutableListOf<String>()
        val pane = pane(emitted)
        onEdt {
            pane.render("hello", emptyList(), true)
            pane.document.insertString(5, "!", null)
            assertEquals("hello!", pane.text)
            assertEquals(listOf("hello!"), emitted)
        }
    }

    @Test
    fun `focus loss cancels provisional text and subsequent typing is emitted`() {
        val emitted = mutableListOf<String>()
        val pane = pane(emitted)
        onEdt {
            pane.render("hello", emptyList(), true)
            pane.caretPosition = 5
            pane.imeEvent(composing = "ㄘㄜ")
            pane.loseFocus()
            assertEquals("hello", pane.text)
            assertEquals(emptyList(), emitted)
            pane.document.insertString(5, "!", null)
            assertEquals(listOf("hello!"), emitted)
        }
    }

    @Test
    fun `commit before focus loss is retained`() {
        val emitted = mutableListOf<String>()
        val pane = pane(emitted)
        onEdt {
            pane.imeEvent(composing = "ㄘㄜ")
            pane.imeEvent(committed = "測試")
            pane.loseFocus()
            assertEquals("測試", pane.text)
            assertEquals(listOf("測試"), emitted)
        }
    }

    @Test
    fun `partial commit stays suppressed until composition finishes`() {
        val emitted = mutableListOf<String>()
        val pane = pane(emitted)
        onEdt {
            pane.imeEvent(composing = "ㄘㄜ")
            pane.imeEvent(committed = "測", composing = "ㄕ")
            assertEquals(emptyList(), emitted)
            pane.render("stale", emptyList(), true)
            pane.imeEvent(committed = "試")
            assertEquals("測試", pane.text)
            // Removing the last provisional range exposes the already committed prefix first.
            assertEquals(listOf("測", "測試"), emitted)
        }
    }

    @Test
    fun `committed edits realign the edited paragraph without changing the majority`() {
        val emitted = mutableListOf<String>()
        val pane = pane(emitted)
        onEdt {
            pane.render("first\nsecond\nthird", emptyList(), true)
            pane.caretPosition = 6
            pane.imeEvent(composing = "ا")
            pane.imeEvent(committed = "مرحبا ")
        }
        // Drain the offset-aware deferred direction update.
        onEdt {
            val root = pane.styledDocument.defaultRootElement
            assertEquals(StyleConstants.ALIGN_LEFT, StyleConstants.getAlignment(root.getElement(0).attributes))
            assertEquals(StyleConstants.ALIGN_RIGHT, StyleConstants.getAlignment(root.getElement(1).attributes))
            assertEquals(StyleConstants.ALIGN_LEFT, StyleConstants.getAlignment(root.getElement(2).attributes))
            assertEquals(true, pane.componentOrientation.isLeftToRight)
            assertEquals(listOf("first\nمرحبا second\nthird"), emitted)
        }
    }
}

package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.event.InputMethodEvent
import java.text.AttributedString
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Typing Chinese, Japanese or Korean goes through an input method: the keystrokes first appear as
 * provisional characters (Bopomofo, kana, jamo) that the IME later replaces with the real word.
 * Swing keeps those provisional characters inside the document and remembers where they are with
 * two Positions.
 *
 * Rewriting the document while that is going on collapses those Positions, so the IME's next
 * event removes nothing and the provisional characters stay behind for good — typing 測試 landed
 * in the pane as ㄘㄜ測ㄕ測試. The rewrite came from the pane's own state cycle: text is reported
 * upwards on every document change and echoed back through a background dispatcher, so an echo
 * carrying older text can arrive mid-composition and be written over the live one.
 */
class AdvancedTextPaneCompositionTest {

    /**
     * One input-method event. [committed] is text the IME has settled on and hands over for good;
     * [composing] is still provisional and will be replaced by the next event.
     */
    private fun AdvancedTextPane.imeEvent(committed: String, composing: String) {
        dispatchEvent(
            InputMethodEvent(
                this,
                InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                AttributedString(committed + composing).iterator,
                committed.length,
                null,
                null,
            )
        )
    }

    private fun pane(onTextChanged: (String) -> Unit = {}): AdvancedTextPane {
        lateinit var created: AdvancedTextPane
        SwingUtilities.invokeAndWait {
            created = AdvancedTextPane(
                onTextChanged = onTextChanged,
                onTranslateRequest = {},
                onListenRequest = {},
            )
        }
        return created
    }

    @Test
    fun `a stale state echo during composition does not strand the half-typed characters`() {
        val emitted = mutableListOf<String>()
        val pane = pane { emitted += it }

        SwingUtilities.invokeAndWait {
            pane.imeEvent(committed = "", composing = "ㄘ")
            pane.imeEvent(committed = "", composing = "ㄘㄜ")

            // The state cycle echoing back what the pane said a keystroke ago. It disagrees with
            // what the pane now holds, which is the condition render() rewrites the document on.
            pane.render("ㄘ", emptyList(), isEditable = true)

            // The IME resolves the syllables into the word and commits it.
            pane.imeEvent(committed = "測試", composing = "")
        }

        assertEquals("測試", pane.text)
        // Half-typed Bopomofo is not text the app should translate, spell-check or store.
        assertEquals(listOf("測試"), emitted)
    }

    @Test
    fun `text typed without an input method is still reported upwards`() {
        val emitted = mutableListOf<String>()
        val pane = pane { emitted += it }

        SwingUtilities.invokeAndWait {
            pane.render("hello", emptyList(), isEditable = true)
            pane.document.insertString(5, "!", null)
        }

        assertEquals("hello!", pane.text)
        assertEquals(listOf("hello!"), emitted)
    }
}

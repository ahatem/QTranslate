package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.GraphemeBoundary
import com.github.ahatem.qtranslate.ui.swing.shared.textpane.MenuShortcutModifier
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.text.BreakIterator
import javax.accessibility.AccessibleText
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.DefaultHighlighter
import javax.swing.text.LabelView
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import javax.swing.text.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression suite for [AdvancedTextPane], deterministic and bound to the event dispatch thread
 * through [onEdt]. No robot interaction and no timing thresholds.
 */
class AdvancedTextPaneTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun newPane(
        onTextChanged: (String) -> Unit = {},
        onTranslate: (String) -> Unit = {},
        onListen: (String) -> Unit = {},
    ): AdvancedTextPane = onEdt {
        AdvancedTextPane(
            onTextChanged = onTextChanged,
            onTranslateRequest = onTranslate,
            onListenRequest = onListen,
        )
    }

    /** Lets the batched font-fallback timer fire, then drains the event queue. */
    private fun settle() {
        Thread.sleep(300)
        onEdt { }
    }

    private fun trigger(pane: AdvancedTextPane, action: String) {
        val event = ActionEvent(pane, ActionEvent.ACTION_PERFORMED, action)
        onEdt { pane.actionMap.get(action)?.actionPerformed(event) }
    }

    // -----------------------------------------------------------------------
    // Undo / document separation
    // -----------------------------------------------------------------------

    @Test
    fun `font fallback pass never becomes a user-visible undo step`() {
        val pane = newPane()
        onEdt { pane.render("hello world", emptyList(), true) }
        settle()

        assertFalse(
            onEdt { pane.undoManager.canUndo() },
            "the fallback font pass must not leave anything in the undo history",
        )
    }

    @Test
    fun `paragraph realignment never becomes a user-visible undo step`() {
        val pane = newPane()
        onEdt { pane.render("hello there", emptyList(), true) }

        // Typing Arabic at the head flips the paragraph's base direction, so the deferred pass
        // rewrites its alignment — a presentation write that must stay out of the undo history.
        onEdt { pane.document.insertString(0, "مرحبا ", null) }
        settle()
        assertEquals(StyleConstants.ALIGN_RIGHT, onEdt { alignmentOf(rootOf(pane), 0) })

        assertTrue(onEdt { pane.undoManager.canUndo() }, "the typed text itself must stay undoable")
        onEdt { pane.undoManager.undo() }
        assertEquals("hello there", onEdt { pane.text })
        assertEquals(StyleConstants.ALIGN_LEFT, onEdt { alignmentOf(rootOf(pane), 0) })
        assertFalse(
            onEdt { pane.undoManager.canUndo() },
            "exactly one undo step — the typed text — should have existed",
        )

        onEdt { pane.undoManager.redo() }
        assertEquals("مرحبا hello there", onEdt { pane.text })
        assertEquals(StyleConstants.ALIGN_RIGHT, onEdt { alignmentOf(rootOf(pane), 0) })
    }

    /**
     * The same edit as the state flow actually delivers it: the pane is rendered with the user's
     * own text, which is where the component follows the document's direction. Assigning
     * component orientation rebuilds the view tree, so undo has to survive it.
     */
    @Test
    fun `undo survives the render that applies right-to-left orientation`() {
        val pane = newPane()
        onEdt { pane.render("hello there", emptyList(), true) }
        onEdt { pane.document.insertString(0, "مرحبا ", null) }
        settle()

        onEdt { pane.render("مرحبا hello there", emptyList(), true) }
        assertFalse(
            onEdt { pane.componentOrientation.isLeftToRight },
            "the component follows the document majority at the render boundary",
        )

        onEdt { pane.undoManager.undo() }
        assertEquals("hello there", onEdt { pane.text })
        onEdt { pane.undoManager.redo() }
        assertEquals("مرحبا hello there", onEdt { pane.text })
    }

    private fun rootOf(pane: AdvancedTextPane) =
        (pane.document as StyledDocument).defaultRootElement

    @Test
    fun `undo reverts typed text even after presentation has run`() {
        val pane = newPane()
        onEdt { pane.document.insertString(0, "hello", null) }
        settle()

        trigger(pane, "undo")
        assertEquals("", onEdt { pane.text })
    }

    @Test
    fun `typing then undo then redo round-trips`() {
        val pane = newPane()
        onEdt { pane.document.insertString(0, "abc", null) }
        trigger(pane, "undo")
        assertEquals("", onEdt { pane.text })
        trigger(pane, "redo")
        assertEquals("abc", onEdt { pane.text })
    }

    @Test
    fun `clear is undoable`() {
        val pane = newPane()
        onEdt { pane.render("keep me", emptyList(), true) }
        onEdt { pane.document.remove(0, pane.document.length) }
        assertEquals("", onEdt { pane.text })
        trigger(pane, "undo")
        assertEquals("keep me", onEdt { pane.text })
    }

    // -----------------------------------------------------------------------
    // Content and callback contract
    // -----------------------------------------------------------------------

    @Test
    fun `programmatic render is not reported as a user edit`() {
        var reported = 0
        val pane = newPane(onTextChanged = { reported++ })

        onEdt { pane.render("hello", emptyList(), true) }
        assertEquals(0, reported, "render must not echo back through the text-changed callback")

        onEdt { pane.document.insertString(0, "x", null) }
        assertEquals(1, reported)
        assertEquals("xhello", onEdt { pane.text })
    }

    @Test
    fun `repeated render with the same text is a no-op`() {
        var reported = 0
        val pane = newPane(onTextChanged = { reported++ })
        onEdt { pane.render("hello", emptyList(), true) }
        onEdt { pane.render("hello", emptyList(), true) }
        assertEquals(0, reported)
        assertEquals("hello", onEdt { pane.text })
    }

    // -----------------------------------------------------------------------
    // Caret / selection preservation
    // -----------------------------------------------------------------------

    @Test
    fun `decorations and font updates preserve caret and selection`() {
        val pane = newPane()
        onEdt { pane.render("the quick brown fox", emptyList(), true) }
        onEdt { pane.select(4, 9) } // selection [4, 9), caret at 9

        onEdt {
            pane.render(
                "the quick brown fox",
                listOf(Correction("quick", 4, 9, listOf("fast"))),
                true,
            )
        }

        assertEquals(9, onEdt { pane.caretPosition })
        assertEquals(4, onEdt { pane.selectionStart })
        assertEquals(9, onEdt { pane.selectionEnd })
    }

    @Test
    fun `render echoing the user's own typing leaves the caret alone`() {
        val pane = newPane()
        onEdt { pane.render("", emptyList(), true) }
        onEdt { pane.document.insertString(0, "مرحبا", null) }
        val caretAfterTyping = onEdt { pane.caretPosition }
        assertEquals(5, caretAfterTyping)

        // The state flow rounds the user's own text back through render.
        onEdt { pane.render("مرحبا", emptyList(), true) }
        assertEquals(caretAfterTyping, onEdt { pane.caretPosition })
    }

    /** `JTextComponent.setText` semantics: the caret lands at the end of the replacement text. */
    @Test
    fun `authoritative new text places the caret at the end of the new text`() {
        val pane = newPane()
        onEdt { pane.render("abcdef", emptyList(), true) }
        onEdt { pane.caretPosition = 3 }
        onEdt { pane.render("brand new translation", emptyList(), true) }
        assertEquals("brand new translation".length, onEdt { pane.caretPosition })
    }

    // -----------------------------------------------------------------------
    // Direction
    // -----------------------------------------------------------------------

    @Test
    fun `isRTL classifies a range of scripts and neutral text`() {
        assertFalse("hello world".isRtlForTest())
        assertTrue("مرحبا بالعالم".isRtlForTest())
        assertTrue("שלום עולם".isRtlForTest())
        assertFalse("".isRtlForTest())
        assertFalse("12345 !!!".isRtlForTest())
        assertTrue("  مرحبا".isRtlForTest())
    }

    @Test
    fun `each paragraph is aligned to its own direction`() {
        val pane = newPane()
        onEdt { pane.render("hello\n\nمرحبا", emptyList(), true) }
        val root = onEdt { (pane.document as StyledDocument).defaultRootElement }

        assertEquals(StyleConstants.ALIGN_LEFT, alignmentOf(root, 0))
        assertEquals(StyleConstants.ALIGN_RIGHT, alignmentOf(root, 2))
    }

    @Test
    fun `component orientation follows the document majority`() {
        val rtlMajority = newPane()
        onEdt { rtlMajority.render("مرحبا\nمرحبا\nhello", emptyList(), true) }
        assertFalse(onEdt { rtlMajority.componentOrientation.isLeftToRight })

        val ltrMajority = newPane()
        onEdt { ltrMajority.render("hello\nhello\nمرحبا", emptyList(), true) }
        assertTrue(onEdt { ltrMajority.componentOrientation.isLeftToRight })
    }

    @Test
    fun `typing a direction-changing character realigns the paragraph`() {
        val pane = newPane()
        onEdt { pane.render("", emptyList(), true) }
        onEdt { pane.document.insertString(0, "مرحبا", null) }
        settle()

        val root = onEdt { (pane.document as StyledDocument).defaultRootElement }
        assertEquals(StyleConstants.ALIGN_RIGHT, alignmentOf(root, 0))
    }

    private fun alignmentOf(root: javax.swing.text.Element, index: Int): Int =
        StyleConstants.getAlignment(root.getElement(index).attributes)

    private fun String.isRtlForTest(): Boolean = this.isRTL()

    // -----------------------------------------------------------------------
    // Unicode
    // -----------------------------------------------------------------------

    @Test
    fun `grapheme segmentation keeps complex clusters together`() {
        assertEquals(1, clusters("\uD83D\uDE00"), "surrogate pair (emoji)")
        assertEquals(1, clusters("a\u0301"), "base letter + combining mark")
        assertEquals(1, clusters("\uD83D\uDC4D\uD83C\uDFFD"), "emoji + skin-tone modifier")
        assertEquals(1, clusters("\uD83C\uDDFA\uD83C\uDDF8"), "regional-indicator flag")
        assertEquals(
            1,
            clusters("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"),
            "zero-width-joiner family sequence",
        )
        assertEquals(2, clusters("ab"), "two plain letters stay two clusters")
    }

    @Test
    fun `long unbreakable grapheme run is laid out without altering the text`() {
        val text = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67".repeat(400)
        val pane = newPane()
        onEdt {
            pane.size = Dimension(120, 240)
            pane.render(text, emptyList(), true)
            val scroll = JScrollPane(pane)
            scroll.setSize(120, 240)
            scroll.doLayout()
            pane.preferredSize
        }
        assertEquals(text, onEdt { pane.text })
    }

    @Test
    fun `long cjk run is laid out without altering the text`() {
        val text = "快速翻译帮助读者轻松理解外文文本".repeat(100)
        val pane = newPane()
        onEdt {
            pane.size = Dimension(160, 200)
            pane.render(text, emptyList(), true)
            JScrollPane(pane).apply { setSize(160, 200); doLayout() }
            pane.preferredSize
        }
        assertEquals(text, onEdt { pane.text })
    }

    /**
     * Number of grapheme clusters in [s].
     *
     * A [BreakIterator] reports boundaries, not clusters, and includes the position before the
     * first character, so the intervals are one fewer than the boundaries.
     */
    private fun clusters(s: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(s)
        var boundaries = 0
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            boundaries++
            boundary = iterator.next()
        }
        return boundaries - 1
    }

    // -----------------------------------------------------------------------
    // Keyboard / platform
    // -----------------------------------------------------------------------

    @Test
    fun `copy, select all and cut use the platform menu shortcut`() {
        val pane = newPane()
        val menuMask = MenuShortcutModifier.current()
        assertEquals("copy-to-clipboard", onEdt { pane.inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask)) })
        assertEquals("select-all", onEdt { pane.inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_A, menuMask)) })
        assertEquals("cut-to-clipboard", onEdt { pane.inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_X, menuMask)) })
    }

    @Test
    fun `undo uses the menu shortcut and redo is available on both conventions`() {
        val pane = newPane()
        val menuMask = MenuShortcutModifier.current()
        assertEquals("undo", onEdt { pane.inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask)) })

        val shiftRedo = KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask or InputEvent.SHIFT_DOWN_MASK)
        assertEquals("redo", onEdt { pane.inputMap.get(shiftRedo) })
        if (menuMask == InputEvent.CTRL_DOWN_MASK) {
            assertEquals(
                "redo",
                onEdt { pane.inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)) },
            )
        }
    }

    @Test
    fun `tab traverses focus while ctrl-tab inserts a literal tab`() {
        val pane = newPane()
        assertEquals("tab-forward", onEdt { pane.inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)) })
        assertEquals(
            "tab-backward",
            onEdt { pane.inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK)) },
        )

        onEdt { pane.render("a", emptyList(), true) }
        onEdt { pane.caretPosition = 1 }
        trigger(pane, "tab-insert")
        assertEquals("a\t", onEdt { pane.text })
    }

    @Test
    fun `translate binding installs on a free stroke and reports the text`() {
        val requests = mutableListOf<String>()
        val pane = newPane(onTranslate = { requests += it })
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.ALT_DOWN_MASK)

        assertTrue(onEdt { pane.setTranslateKeyStroke(null, stroke) })
        onEdt { pane.render("hello", emptyList(), true); pane.selectAll() }
        trigger(pane, "translate")

        assertEquals(listOf("hello"), requests)
    }

    @Test
    fun `translate binding refuses to overwrite a core action`() {
        val pane = newPane()
        val menuMask = MenuShortcutModifier.current()
        val copy = KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask)

        val accepted = onEdt { pane.setTranslateKeyStroke(null, copy) }

        assertFalse(accepted, "a colliding translate shortcut must be refused, not installed")
        assertEquals("copy-to-clipboard", onEdt { pane.inputMap.get(copy) })
    }

    // -----------------------------------------------------------------------
    // Platform menu shortcut resolution
    // -----------------------------------------------------------------------

    @Test
    fun `the headless fallback uses Ctrl on Windows and Linux`() {
        assertEquals(InputEvent.CTRL_DOWN_MASK, MenuShortcutModifier.fallback("Windows 11"))
        assertEquals(InputEvent.CTRL_DOWN_MASK, MenuShortcutModifier.fallback("Linux"))
        assertEquals(InputEvent.CTRL_DOWN_MASK, MenuShortcutModifier.fallback(null))
    }

    @Test
    fun `the headless fallback uses Command on macOS`() {
        assertEquals(InputEvent.META_DOWN_MASK, MenuShortcutModifier.fallback("Mac OS X"))
        assertEquals(InputEvent.META_DOWN_MASK, MenuShortcutModifier.fallback("macOS"))
    }

    @Test
    fun `a headful platform takes its modifier from the toolkit`() {
        var asked = false
        val mask = MenuShortcutModifier.resolve(headless = false, osName = "Linux", toolkitMask = {
            asked = true
            InputEvent.META_DOWN_MASK
        })

        assertTrue(asked, "the toolkit supplies the modifier when one is available")
        assertEquals(InputEvent.META_DOWN_MASK, mask)
    }

    @Test
    fun `a headless platform never asks the toolkit`() {
        var asked = false
        val mask = MenuShortcutModifier.resolve(headless = true, osName = "Mac OS X", toolkitMask = {
            asked = true
            InputEvent.CTRL_DOWN_MASK
        })

        assertFalse(asked, "the headful-only toolkit must not be consulted when headless")
        assertEquals(InputEvent.META_DOWN_MASK, mask)
    }

    @Test
    fun `the resolved modifier follows the environment it runs in`() {
        val resolved = MenuShortcutModifier.current()

        if (GraphicsEnvironment.isHeadless()) {
            assertEquals(MenuShortcutModifier.fallback(System.getProperty("os.name")), resolved)
        } else {
            assertEquals(Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx, resolved)
        }
    }

    /** The construction step that failed on the headless CI runner. */
    @Test
    fun `the pane is constructible in the current environment`() {
        assertNotNull(newPane())
    }

    // -----------------------------------------------------------------------
    // Read-only
    // -----------------------------------------------------------------------

    @Test
    fun `read-only pane stays focusable and selectable without mutating`() {
        val pane = newPane()
        onEdt { pane.render("read only text", emptyList(), false) }

        assertFalse(onEdt { pane.isEditable })
        assertTrue(onEdt { pane.isFocusable })
        onEdt { pane.selectAll() }
        assertEquals("read only text", onEdt { pane.selectedText })
        assertEquals("read only text", onEdt { pane.text })
    }

    // -----------------------------------------------------------------------
    // Decorations and accessibility
    // -----------------------------------------------------------------------

    @Test
    fun `out-of-range corrections are ignored without disturbing text or other highlights`() {
        val pane = newPane()
        onEdt { pane.render("short", listOf(Correction("stale", 100, 200, emptyList())), true) }
        assertEquals("short", onEdt { pane.text })
        assertEquals(0, onEdt { (pane.highlighter as DefaultHighlighter).highlights.size })

        onEdt { pane.render("short", listOf(Correction("short", 0, 5, emptyList())), true) }
        assertEquals(1, onEdt { (pane.highlighter as DefaultHighlighter).highlights.size })
        assertEquals("short", onEdt { pane.text })
    }

    @Test
    fun `hint and character counter are not part of the document or its accessible text`() {
        val pane = newPane()
        onEdt {
            pane.hintText = "Type something"
            pane.showCharCount = true
            pane.render("hi", emptyList(), true)
        }

        assertEquals("hi", onEdt { pane.text })
        val accessible = onEdt { pane.accessibleContext as AccessibleText }
        assertEquals(2, accessible.getCharCount())
    }

    @Test
    fun `accessible text still reflects the document selection`() {
        val pane = newPane()
        onEdt { pane.render("hello world", emptyList(), true) }
        onEdt { pane.select(0, 5) }

        val accessible = onEdt { pane.accessibleContext as AccessibleText }
        assertEquals("hello", accessible.getSelectedText())
    }

    // -----------------------------------------------------------------------
    // Translate rebinding is atomic
    // -----------------------------------------------------------------------

    private fun altT() = KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.ALT_DOWN_MASK)
    private fun altR() = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK)
    private fun copyStroke() =
        KeyStroke.getKeyStroke(KeyEvent.VK_C, MenuShortcutModifier.current())

    @Test
    fun `a free stroke is installed as the translate binding`() {
        val pane = newPane()
        val stroke = altT()

        assertTrue(onEdt { pane.setTranslateKeyStroke(null, stroke) })
        assertEquals("translate", onEdt { pane.inputMap.get(stroke) })
    }

    @Test
    fun `translate rebinds from one free stroke to another`() {
        val pane = newPane()
        val first = altT()
        val second = altR()

        assertTrue(onEdt { pane.setTranslateKeyStroke(null, first) })
        assertTrue(onEdt { pane.setTranslateKeyStroke(first, second) })

        assertNull(onEdt { pane.inputMap.get(first) })
        assertEquals("translate", onEdt { pane.inputMap.get(second) })
    }

    @Test
    fun `a conflicting rebind is rejected and changes nothing`() {
        val pane = newPane()
        val previous = altT()
        val copy = copyStroke()
        assertTrue(onEdt { pane.setTranslateKeyStroke(null, previous) })

        assertFalse(onEdt { pane.setTranslateKeyStroke(previous, copy) })

        // The previous shortcut survives the rejection...
        assertEquals("translate", onEdt { pane.inputMap.get(previous) })
        // ...and the command it collided with is untouched.
        assertEquals("copy-to-clipboard", onEdt { pane.inputMap.get(copy) })
    }

    @Test
    fun `a null stroke intentionally removes the translate binding`() {
        val pane = newPane()
        val stroke = altT()

        assertTrue(onEdt { pane.setTranslateKeyStroke(null, stroke) })
        assertTrue(onEdt { pane.setTranslateKeyStroke(stroke, null) })

        assertNull(onEdt { pane.inputMap.get(stroke) })
    }

    @Test
    fun `rebinding to the same stroke is idempotent`() {
        val pane = newPane()
        val stroke = altT()

        assertTrue(onEdt { pane.setTranslateKeyStroke(null, stroke) })
        assertTrue(onEdt { pane.setTranslateKeyStroke(stroke, stroke) })
        assertEquals("translate", onEdt { pane.inputMap.get(stroke) })

        assertTrue(onEdt { pane.setTranslateKeyStroke(stroke, stroke) })
        assertEquals("translate", onEdt { pane.inputMap.get(stroke) })
    }

    @Test
    fun `releasing a stroke owned by another action leaves that action alone`() {
        val pane = newPane()
        val copy = copyStroke()
        val free = altT()

        assertTrue(onEdt { pane.setTranslateKeyStroke(copy, free) })

        assertEquals("copy-to-clipboard", onEdt { pane.inputMap.get(copy) })
        assertEquals("translate", onEdt { pane.inputMap.get(free) })
    }

    // -----------------------------------------------------------------------
    // Grapheme-safe wrapping (the boundary the view actually chooses)
    // -----------------------------------------------------------------------

    @Test
    fun `a proposed break inside a cluster is moved back to the cluster start`() {
        val boundary = GraphemeBoundary()

        assertEquals(0, boundary.lastAtOrBelow("\uD83D\uDE00x", 1), "surrogate pair")
        assertEquals(2, boundary.lastAtOrBelow("\uD83D\uDE00x", 2), "surrogate pair")

        assertEquals(0, boundary.lastAtOrBelow("a\u0301x", 1), "base + combining mark")
        assertEquals(2, boundary.lastAtOrBelow("a\u0301x", 2), "base + combining mark")

        assertEquals(0, boundary.lastAtOrBelow("\uD83D\uDC4D\uD83C\uDFFDx", 3), "emoji + skin tone")
        assertEquals(4, boundary.lastAtOrBelow("\uD83D\uDC4D\uD83C\uDFFDx", 4), "emoji + skin tone")

        assertEquals(0, boundary.lastAtOrBelow("\uD83C\uDDFA\uD83C\uDDF8x", 2), "flag")
        assertEquals(4, boundary.lastAtOrBelow("\uD83C\uDDFA\uD83C\uDDF8x", 4), "flag")

        assertEquals(0, boundary.lastAtOrBelow("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67x", 7), "zwj")
        assertEquals(8, boundary.lastAtOrBelow("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67x", 8), "zwj")

        assertEquals(1, boundary.lastAtOrBelow("ab", 1), "plain letters")
    }

    @Test
    fun `breakView never chooses a line boundary inside a grapheme cluster`() {
        assertBreakViewAvoidsClusterSplits("\uD83D\uDE00", "surrogate pair")
        assertBreakViewAvoidsClusterSplits("a\u0301", "base + combining mark")
        assertBreakViewAvoidsClusterSplits("\uD83D\uDC4D\uD83C\uDFFD", "emoji + skin tone")
        assertBreakViewAvoidsClusterSplits("\uD83C\uDDFA\uD83C\uDDF8", "regional-indicator flag")
        assertBreakViewAvoidsClusterSplits(
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
            "zero-width-joiner sequence",
        )
    }

    /**
     * Whether a run wraps at all depends on the font's glyph advances, so this asserts only what
     * the laid out views must satisfy: they begin at the first character and cover the text in
     * order without gaps. The grapheme guarantee itself is asserted by the width sweep below.
     */
    @Test
    fun `laid out fragments cover an unbreakable run without gaps`() {
        val text = "\uD83C\uDDFA\uD83C\uDDF8".repeat(60)
        val fragments = laidOutFragments(text)

        assertEquals(0, fragments.first().startOffset)
        assertTrue(
            fragments.last().endOffset >= text.length,
            "the last fragment must reach the end of the run",
        )
        fragments.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.endOffset, next.startOffset, "wrapped fragments must not leave gaps")
        }
    }

    /**
     * Drives the view's own break decision across a range of available widths. One fixed width can
     * pass by luck, since whether it lands mid-cluster depends on the font's glyph advances.
     *
     * Only `breakView` is asserted. The paragraph is a `FlowView`, so the offsets in the laid out
     * view tree are chosen by Swing's `LineBreakMeasurer`, which cuts a run that fits nowhere else
     * and can land mid-cluster; that choice is not this view's.
     */
    private fun assertBreakViewAvoidsClusterSplits(cluster: String, label: String) {
        val text = cluster.repeat(60)
        val validBoundaries = acceptableBoundariesOf(text)
        val runView = laidOutFragments(text).first { it.startOffset == 0 }

        for (available in 1..160) {
            val fragment = onEdt { runView.breakView(View.X_AXIS, runView.startOffset, 0f, available.toFloat()) }
            if (fragment == null || fragment === runView) continue
            assertTrue(
                fragment.endOffset in validBoundaries,
                "$label: a ${available}px line would cut a grapheme cluster at offset ${fragment.endOffset}",
            )
        }
    }

    /** Renders [text], lays the pane out narrow, and returns its leaf views in document order. */
    private fun laidOutFragments(text: String): List<View> {
        val pane = newPane()
        // An explicit font keeps the glyph advances independent of whichever look and feel the
        // surrounding test run happens to have installed.
        onEdt { pane.updateFontsAndRescanDocument(Font("Monospaced", Font.PLAIN, 14), Font("Dialog", Font.PLAIN, 14)) }
        onEdt { pane.render(text, emptyList(), true) }
        settle()
        layOutPane(pane, 60, 300)
        return onEdt {
            val leaves = mutableListOf<View>()
            collectLeafViews(pane.ui.getRootView(pane), leaves)
            leaves.toList()
        }
    }

    /**
     * The paragraph is a `FlowView`, whose rows exist only once it is laid out at a width. A
     * `JScrollPane` and a preferred-size query do not drive that; painting does, and is headless.
     */
    private fun layOutPane(pane: AdvancedTextPane, width: Int, height: Int) {
        onEdt {
            pane.size = Dimension(width, height)
            JScrollPane(pane).apply { setSize(width, height); doLayout() }
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                pane.paint(graphics)
            } finally {
                graphics.dispose()
            }
        }
    }

    private fun collectLeafViews(view: View, out: MutableList<View>) {
        if (view.viewCount == 0) {
            out.add(view)
            return
        }
        for (index in 0 until view.viewCount) collectLeafViews(view.getView(index), out)
    }

    private fun clusterBoundariesOf(text: String): Set<Int> {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val boundaries = mutableSetOf<Int>()
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            boundaries.add(boundary)
            boundary = iterator.next()
        }
        return boundaries
    }

    /**
     * Cluster boundaries plus the end-of-element positions.
     *
     * A view's end offset is exclusive, so the last fragment of a character element ends at
     * `length + 1` rather than at a text index, which is not a break inside a cluster.
     */
    private fun acceptableBoundariesOf(text: String): Set<Int> =
        (clusterBoundariesOf(text) + setOf(text.length, text.length + 1))

    // -----------------------------------------------------------------------
    // Fallback font metric alignment survives the document
    // -----------------------------------------------------------------------

    /**
     * Follows the production path and reads the font back out of the `LabelView` that paints the
     * run; fails if only the raw fallback size survives.
     */
    @Test
    fun `a fallback run is rendered at the metric-aligned size the view resolves`() {
        val primary = Font("SansSerif", Font.PLAIN, 8)
        val rawFallback = Font("Serif", Font.PLAIN, 28)
        val pane = newPane()

        onEdt { pane.updateFontsAndRescanDocument(primary, rawFallback) }
        settle()
        onEdt { pane.document.insertString(0, "hello world", null) }
        settle()

        onEdt { pane.undoManager.discardAllEdits() }
        onEdt { pane.applyFallbackFontAttributes(0, 5, pane.fallbackFont) }
        settle()

        assertFalse(onEdt { pane.undoManager.canUndo() }, "fallback attributes must never be undoable")

        layOutPane(pane, 400, 120)
        val runs = onEdt {
            val leaves = mutableListOf<View>()
            collectLeafViews(pane.ui.getRootView(pane), leaves)
            leaves.map { Triple(it.startOffset, it.endOffset, (it as? LabelView)?.getFont()) }
        }

        val fallbackRun = runs.firstOrNull { it.first <= 0 && it.second > 0 }?.third
        val primaryRun = runs.firstOrNull { it.first <= 10 && it.second > 10 }?.third

        assertNotNull(fallbackRun, "no view covers the fallback run")
        assertNotNull(primaryRun, "no view covers the primary run")

        assertEquals(rawFallback.family, fallbackRun.family)
        assertEquals(primary.size, fallbackRun.size, "the fallback must not be drawn at its raw size")
        assertTrue(
            fallbackRun.size < rawFallback.size,
            "raw fallback size leaked through: ${fallbackRun.size}",
        )
        assertEquals(
            ascentOf(primary),
            ascentOf(fallbackRun),
            1.0f,
            "the fallback's ascent should match the primary's, so baselines line up",
        )

        assertEquals(primary.family, primaryRun.family)
        assertEquals(primary.size, primaryRun.size)
    }

    private fun ascentOf(font: Font): Float =
        font.getLineMetrics("A", FontRenderContext(null, true, true)).ascent
}

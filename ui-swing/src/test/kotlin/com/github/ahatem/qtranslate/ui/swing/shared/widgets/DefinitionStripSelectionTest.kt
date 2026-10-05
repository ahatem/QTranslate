package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import javax.swing.JMenuItem
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The definition beneath a translation is selectable and copyable in its own right, and taking it
 * leaves the strip exactly as it was otherwise.
 */
class DefinitionStripSelectionTest {

    private val definition = "a short definition of the word"

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun strip(definition: String = this.definition): DefinitionStrip {
        val strip = DefinitionStrip(showDivider = false)
        onEdt {
            strip.render(definition)
            strip.setSize(300, 200)
        }
        return strip
    }

    private fun menuItems(strip: DefinitionStrip): List<JMenuItem> {
        val menu = strip.textComponent.componentPopupMenu
        assertNotNull(menu, "a right-click on a definition opens a menu")
        return menu.components.filterIsInstance<JMenuItem>()
    }

    /** The family that draws character [offset] of the strip's definition. */
    private fun familyAt(strip: DefinitionStrip, offset: Int): String {
        val doc = strip.textComponent.styledDocument
        return StyleConstants.getFontFamily(doc.getCharacterElement(offset).attributes)
    }

    @Test
    fun `a drag across the definition selects part of it`() {
        val strip = strip()

        SelectionMouseDrag.dragAcross(strip.textComponent)

        val selected = SelectionMouseDrag.selectedText(strip.textComponent)
        assertNotNull(selected, "a drag selects: $selected")
        assertTrue(selected.isNotEmpty(), "and selects something: \"$selected\"")
        assertTrue(selected.length < definition.length, "not the whole definition: \"$selected\"")
        assertTrue(definition.contains(selected), "and only what the definition says")
    }

    @Test
    fun `the definition is read-only, focusable, and out of the tab order`() {
        val text = strip().textComponent

        assertFalse(text.isEditable, "a definition is not typed into")
        assertTrue(text.isFocusable, "a click leaves the caret here so the keyboard can copy")
        assertTrue(text.skipsFocusTraversal(), "while Tab walks past it")
    }

    @Test
    fun `the definition offers Copy and Select All, and nothing else`() {
        val strip = strip()

        assertEquals(
            listOf("Copy", "Select All"),
            menuItems(strip).map { it.text },
            "Cut and Paste are omitted because a definition cannot be typed into"
        )
    }

    @Test
    fun `the definition's copy is its own, never the translation above it`() {
        val strip = strip()
        val translation = JTextPane().apply {
            text = "the translated text above the definition"
            isEditable = false
            setSize(300, 40)
        }

        SelectionMouseDrag.selectAll(strip.textComponent)
        SelectionMouseDrag.selectAll(translation)

        assertEquals(definition, strip.textComponent.selectedText, "the strip holds the definition")
        assertNotSame(
            strip.textComponent.componentPopupMenu,
            translation.componentPopupMenu,
            "the two surfaces do not share a menu, so one cannot copy the other's text"
        )
    }

    @Test
    fun `the definition's labels come from the shared Copy and Select All strings`() {
        val strip = strip()
        onEdt {
            strip.contextMenuLabels = { key ->
                if (key == SelectionContextMenu.COPY_KEY) "Copier" else "Tout"
            }
        }
        strip.textComponent.selectionContextMenu()!!.refresh()

        assertEquals(
            listOf("Copier", "Tout"),
            menuItems(strip).map { it.text },
            "resolved from common.copy and common.select_all rather than a second set"
        )
    }

    @Test
    fun `a new definition starts from no selection`() {
        val strip = strip()
        SelectionMouseDrag.selectAll(strip.textComponent)
        assertEquals(definition, SelectionMouseDrag.selectedText(strip.textComponent))

        onEdt { strip.render("a different definition entirely") }

        assertNull(
            SelectionMouseDrag.selectedText(strip.textComponent),
            "the old selection did not carry over onto text that is no longer there"
        )
    }

    @Test
    fun `selecting the definition does not change how it is drawn`() {
        val sample = "ภาษาไทย meaning in Thai"
        val strip = strip(sample)

        val runsBefore = (0 until sample.length).map { familyAt(strip, it) }
        SelectionMouseDrag.selectAll(strip.textComponent)
        val runsAfter = (0 until sample.length).map { familyAt(strip, it) }

        assertEquals(runsBefore, runsAfter, "the same faces drew it before and after")
        assertEquals(sample, strip.textComponent.text, "and the text itself is untouched")
    }

    @Test
    fun `selecting the definition does not add it to the tab order`() {
        val strip = strip()
        SelectionMouseDrag.dragAcross(strip.textComponent)

        assertTrue(strip.textComponent.skipsFocusTraversal(), "still skipped after selecting")
    }
}
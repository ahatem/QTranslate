package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import javax.swing.JMenuItem
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Copy and Select All are the only two actions a read-only selectable surface can honour, and each
 * acts on its own component's selection rather than anything else on screen.
 */
class SelectionContextMenuTest {

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    /** Built the way a definition or an example's text area is built. */
    private fun readOnlyArea(text: String): JTextArea {
        val area = JTextArea(text)
        onEdt {
            area.lineWrap = true
            area.wrapStyleWord = true
            area.isEditable = false
            area.isFocusable = true
            area.skipFocusTraversal()
            area.setSize(500, 40)
        }
        return area
    }

    @Test
    fun `Copy is off with no selection, and on once a phrase is selected`() {
        val area = readOnlyArea("a definition")
        val copies = mutableListOf<JTextComponent>()
        val menu = SelectionContextMenu(area, copyAction = { copies += it })

        SelectionMouseDrag.clearSelection(area)
        menu.refresh()
        assertFalse(menu.copyItem.isEnabled, "Copy with no selection has nothing to copy")
        assertTrue(menu.selectAllItem.isEnabled, "Select All with text has something to take")

        menu.copyItem.doClick()
        assertTrue(copies.isEmpty(), "and clicking it anyway copies nothing")

        SelectionMouseDrag.dragAcross(area)
        menu.refresh()
        assertTrue(menu.copyItem.isEnabled, "Copy is offered once a phrase is selected")
    }

    @Test
    fun `neither item is offered over an empty area`() {
        val menu = SelectionContextMenu(readOnlyArea(""), copyAction = { })

        menu.refresh()
        assertFalse(menu.selectAllItem.isEnabled, "Select All over nothing selects nothing")
        assertFalse(menu.copyItem.isEnabled, "and Copy is off for the same reason")
    }

    @Test
    fun `Copy takes this component's selection, and only this one`() {
        // Two surfaces side by side, as a definition sits under a translation: a copy from one must
        // be unable to reach the other's text.
        val definition = readOnlyArea("a definition of the word")
        val translation = readOnlyArea("the translated text")
        val copied = mutableListOf<JTextComponent>()

        val definitionMenu = SelectionContextMenu(definition, copyAction = { copied += it })
        SelectionContextMenu(translation, copyAction = { copied += it })

        SelectionMouseDrag.dragAcross(definition)
        definitionMenu.copyItem.doClick()

        assertEquals(1, copied.size, "one copy happened")
        assertSame(definition, copied.single(), "and it was the definition's, not the translation's")
        assertEquals(
            SelectionMouseDrag.selectedText(definition),
            copied.single().selectedText,
            "the copy names the selection as it stood"
        )
        assertNull(translation.selectedText, "and the translation's own selection was left alone")
    }

    @Test
    fun `Select All takes this component's whole text`() {
        val definition = readOnlyArea("a definition of the word")
        val translation = readOnlyArea("the translated text")
        SelectionContextMenu(definition, copyAction = { })
        SelectionContextMenu(translation, copyAction = { })

        SelectionMouseDrag.clearSelection(definition)
        SelectionMouseDrag.selectAll(definition)

        assertEquals(definition.text, definition.selectedText)
        assertEquals(0, definition.selectionStart)
        assertEquals(definition.text.length, definition.selectionEnd)
        assertNull(translation.selectedText, "and the other surface selected nothing")
    }

    @Test
    fun `the menu replaces the stock menu and offers exactly two items`() {
        val area = readOnlyArea("a definition")
        val menu = SelectionContextMenu(area, copyAction = { })

        assertSame(menu.menu, area.componentPopupMenu, "the right-click shows this menu")
        assertEquals(
            listOf("Copy", "Select All"),
            menu.menu.components.map { (it as JMenuItem).text },
            "offering exactly the two that work",
        )
    }

    @Test
    fun `labels are read each time the menu opens`() {
        val menu = SelectionContextMenu(readOnlyArea("a definition"), copyAction = { })

        // A language change arrives as a new provider, not as a rebuild of every menu in the window.
        menu.labels = { key -> if (key == SelectionContextMenu.COPY_KEY) "Copier" else "Tout" }
        menu.refresh()
        menu.labels = { key -> if (key == SelectionContextMenu.COPY_KEY) "Kopieren" else "Alles" }
        menu.refresh()

        assertEquals("Kopieren", menu.copyItem.text)
        assertEquals("Alles", menu.selectAllItem.text)
    }

    @Test
    fun `with no labels the menu keeps its own English text`() {
        val menu = SelectionContextMenu(readOnlyArea("a definition"), copyAction = { })

        menu.refresh()
        assertEquals("Copy", menu.copyItem.text)
        assertEquals("Select All", menu.selectAllItem.text)
    }
}
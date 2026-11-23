package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import javax.swing.JMenuItem
import javax.swing.JTextArea
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
    fun `Copy is off with no selection and on once a phrase is selected, and nothing is offered over an empty area`() {
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

        val empty = SelectionContextMenu(readOnlyArea(""), copyAction = { })
        empty.refresh()
        assertFalse(empty.selectAllItem.isEnabled, "no Select All over an empty area")
        assertFalse(empty.copyItem.isEnabled, "no Copy over an empty area")
    }

    @Test
    fun `Copy takes this component's selection, and only this one`() {
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

}
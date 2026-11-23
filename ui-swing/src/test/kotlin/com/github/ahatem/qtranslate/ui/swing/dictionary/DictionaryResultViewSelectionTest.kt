package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.SelectionContextMenu
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.SelectionMouseDrag
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.selectionContextMenu
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.skipsFocusTraversal
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Container
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JTextArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A reader can select and copy from a dictionary entry without it becoming writable or a Tab stop. */
class DictionaryResultViewSelectionTest {

    private val definition = "a quick brown fox"
    private val example = "the quick brown fox jumped"

    /** An IconManager with no plugin manager behind it; no test here asks it for an icon. */
    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private fun entry(
        definitionText: String = definition,
        definitionExample: String? = example,
        synonyms: List<String> = listOf("vixen"),
    ) = DictionaryEntry(
        word = "fox",
        partOfSpeech = "noun",
        phonetic = "fɒks",
        definitions = listOf(Definition(text = definitionText, example = definitionExample)),
        synonyms = synonyms,
    )

    private fun view(
        entries: List<DictionaryEntry> = listOf(entry()),
        labels: ((String) -> String)? = null,
        onSynonymClicked: ((String) -> Unit)? = null,
    ): DictionaryResultView {
        val view = DictionaryResultView(blindIconManager())
        onEdt {
            view.contextMenuLabels = labels
            view.render(
                entries = entries,
                synonymsLabel = "Synonyms",
                onSynonymClicked = onSynonymClicked,
            )
            view.setSize(600, 800)
        }
        return view
    }

    private fun <T : java.awt.Component> findAll(root: java.awt.Component, type: Class<T>): List<T> {
        val found = mutableListOf<T>()
        fun walk(c: java.awt.Component) {
            if (type.isInstance(c)) found += type.cast(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(root)
        return found
    }

    private fun textAreas(view: DictionaryResultView): List<JTextArea> =
        findAll(view, JTextArea::class.java)

    private fun definitionArea(view: DictionaryResultView, text: String = definition): JTextArea =
        textAreas(view).first { it.text == text }

    private fun exampleArea(view: DictionaryResultView): JTextArea =
        textAreas(view).first { it.text.contains(example) }

    private fun menuItems(component: javax.swing.JComponent): List<JMenuItem> =
        component.componentPopupMenu.components.filterIsInstance<JMenuItem>()

    /** The first item is Copy by construction; the menu only ever holds Copy and Select All. */
    private fun copyItemOf(component: javax.swing.JComponent): JMenuItem = menuItems(component).first()

    @Test
    fun `definitions and examples can be partly selected with the mouse`() {
        val view = view()
        val cases = listOf("definition" to definitionArea(view), "example" to exampleArea(view))
        for ((label, area) in cases) {
            SelectionMouseDrag.dragAcross(area)

            val selected = SelectionMouseDrag.selectedText(area)
            assertNotNull(selected, "$label: drag selects text")
            assertTrue(selected.isNotEmpty(), "$label: selection is empty")
            assertTrue(selected.length < area.text.length, "$label: selected the whole text: \"$selected\"")
            assertTrue(area.text.contains(selected), "$label: selection is not the area's own text: \"$selected\"")
        }
    }

    @Test
    fun `definitions and examples stay read-only, focusable, and out of the tab order`() {
        val areas = textAreas(view())

        assertTrue(areas.size >= 2, "expected a definition and an example: ${areas.map { it.text }}")
        areas.forEach { area ->
            assertFalse(area.isEditable, "editable: \"${area.text}\"")
            assertTrue(area.isFocusable, "not focusable, so the keyboard cannot copy: \"${area.text}\"")
            assertTrue(area.skipsFocusTraversal(), "Tab stops on \"${area.text}\"")
        }
    }

    @Test
    fun `an entry offers Copy and Select All, and nothing else`() {
        val area = definitionArea(view())

        assertNotNull(area.componentPopupMenu, "no context menu")
        assertEquals(
            listOf("Copy", "Select All"),
            menuItems(area).map { it.text },
            "Cut and Paste are omitted: an entry is read-only"
        )
    }

    @Test
    fun `Copy from a definition takes the definition and leaves the example alone`() {
        val view = view()
        val area = definitionArea(view)
        val exampleArea = exampleArea(view)

        SelectionMouseDrag.dragAcross(area)
        copyItemOf(area).doClick()

        assertTrue(
            definition.contains(SelectionMouseDrag.selectedText(area) ?: ""),
            "the definition lost its selection through the copy"
        )
        assertNull(exampleArea.selectedText, "the example was selected too")
    }

    @Test
    fun `the menu is localized through the shared Copy and Select All strings`() {
        val area = definitionArea(view(labels = { key ->
            if (key == SelectionContextMenu.COPY_KEY) "Copier" else "Tout"
        }))

        // Labels are read as the menu opens, so refresh is what a right-click does first.
        area.selectionContextMenu()!!.refresh()

        assertEquals(
            listOf("Copier", "Tout"),
            menuItems(area).map { it.text },
            "labels come from the provider by key"
        )
    }

    @Test
    fun `a label change reaches entries already on screen`() {
        val view = view(labels = { key -> if (key == SelectionContextMenu.COPY_KEY) "Copier" else "Tout" })
        val area = definitionArea(view)
        val menu = area.componentPopupMenu

        onEdt {
            view.contextMenuLabels = { key ->
                if (key == SelectionContextMenu.COPY_KEY) "Kopieren" else "Alles"
            }
        }

        // Relabelled in place: a rebuild would flash the panel blank for a frame.
        assertSame(menu, area.componentPopupMenu, "the menu was replaced")
        area.selectionContextMenu()!!.refresh()
        assertEquals(
            listOf("Kopieren", "Alles"),
            menuItems(area).map { it.text },
            "new labels not shown when the menu next opens"
        )
    }

    @Test
    fun `a synonym is still a button that still looks one up`() {
        val clicked = mutableListOf<String>()
        val view = view(onSynonymClicked = { clicked += it })

        val synonymButton = findAll(view, JButton::class.java).firstOrNull { it.text == "vixen" }
        assertNotNull(synonymButton, "synonym is not a button")

        onEdt { synonymButton.doClick() }

        assertEquals(listOf("vixen"), clicked, "clicking a synonym did not look it up")
    }

    @Test
    fun `unchanged entries are not rebuilt and changed ones are`() {
        val view = view()
        val first = definitionArea(view)

        onEdt { view.render(entries = listOf(entry()), synonymsLabel = "Synonyms") }

        assertSame(first, definitionArea(view), "unchanged entries were rebuilt")

        onEdt {
            view.render(
                entries = listOf(entry(definitionText = "a different sense entirely")),
                synonymsLabel = "Synonyms",
            )
        }

        val rebuilt = definitionArea(view, text = "a different sense entirely")
        assertNotSame(first, rebuilt, "changed entries were not rebuilt")
        assertNull(
            rebuilt.selectedText,
            "the rebuilt area kept the old selection range"
        )
    }
}

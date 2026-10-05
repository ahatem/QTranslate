package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.SelectionContextMenu
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.SelectionMouseDrag
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.selectionContextMenu
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.skipsFocusTraversal
import java.awt.Container
import java.awt.Font
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a reader can do with a dictionary entry: select part of a definition or an example and take
 * it, without the entry becoming writable or a Tab stop.
 *
 * The entry's own styling and render cache are pinned alongside, because a text area that became
 * selectable must not have become something else on the way.
 */
class DictionaryResultViewSelectionTest {

    private val definition = "a quick brown fox"
    private val example = "the quick brown fox jumped"

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

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
    fun `a definition can be selected with the mouse`() {
        val area = definitionArea(view())

        SelectionMouseDrag.dragAcross(area)

        val selected = SelectionMouseDrag.selectedText(area)
        assertNotNull(selected, "a drag over a definition selects part of it")
        assertTrue(selected.isNotEmpty(), "and selects something: \"$selected\"")
        assertTrue(selected.length < definition.length, "not the whole definition: \"$selected\"")
        assertTrue(definition.contains(selected), "only the definition's own text, not its numbering")
    }

    @Test
    fun `an example can be selected with the mouse`() {
        val area = exampleArea(view())

        SelectionMouseDrag.dragAcross(area)

        val selected = SelectionMouseDrag.selectedText(area)
        assertNotNull(selected, "an example is selectable on the same terms as a definition")
        assertTrue(selected.length < area.text.length, "and partially: \"$selected\"")
    }

    @Test
    fun `definitions and examples stay read-only, focusable, and out of the tab order`() {
        val areas = textAreas(view())

        assertTrue(areas.size >= 2, "the entry drew a definition and an example: ${areas.map { it.text }}")
        areas.forEach { area ->
            assertFalse(area.isEditable, "an entry is looked up, never written into: \"${area.text}\"")
            assertTrue(area.isFocusable, "a click leaves the caret here so the keyboard can copy")
            assertTrue(area.skipsFocusTraversal(), "while Tab walks past it")
        }
    }

    @Test
    fun `an entry offers Copy and Select All, and nothing else`() {
        val area = definitionArea(view())

        assertNotNull(area.componentPopupMenu, "a right-click on a definition opens a menu")
        assertEquals(
            listOf("Copy", "Select All"),
            menuItems(area).map { it.text },
            "Cut and Paste are omitted because an entry cannot be typed into"
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
            "the definition kept its own selection through the copy"
        )
        assertNull(exampleArea.selectedText, "and the example was not dragged along")
    }

    @Test
    fun `the menu is localized through the shared Copy and Select All strings`() {
        val area = definitionArea(view(labels = { key ->
            if (key == SelectionContextMenu.COPY_KEY) "Copier" else "Tout"
        }))

        // Labels are read as the menu opens, not when it is built, so the refresh is what a
        // right-click does before anything is drawn.
        area.selectionContextMenu()!!.refresh()

        assertEquals(
            listOf("Copier", "Tout"),
            menuItems(area).map { it.text },
            "the provider is asked for common.copy and common.select_all by key"
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

        // Relabelled in place: rebuilding the entry list to change two words would flash the panel
        // blank for a frame, which is what the render cache exists to prevent.
        assertSame(menu, area.componentPopupMenu, "the same menu is still on the component")
        area.selectionContextMenu()!!.refresh()
        assertEquals(
            listOf("Kopieren", "Alles"),
            menuItems(area).map { it.text },
            "and it shows the new language the next time it opens"
        )
    }

    @Test
    fun `the entry still wraps and still says exactly what it was given`() {
        val long = List(12) { "definition" }.joinToString(" ")
        val area = definitionArea(
            view(entries = listOf(entry(definitionText = long, definitionExample = null))),
            text = long,
        )

        assertEquals(long, area.text, "selectability changed nothing about the text")
        assertTrue(area.lineWrap, "a long definition still wraps")
        assertTrue(area.wrapStyleWord, "and still wraps on word boundaries")
    }

    @Test
    fun `an example is still drawn as an italic aside`() {
        val example = exampleArea(view())

        assertEquals(
            Font.ITALIC,
            example.font.style and Font.ITALIC,
            "the styling that marks an example as an example survived"
        )
        assertFalse(example.isOpaque, "and it is still drawn on the panel behind it")
    }

    @Test
    fun `a synonym is still a button that still looks one up`() {
        val clicked = mutableListOf<String>()
        val view = view(onSynonymClicked = { clicked += it })

        val synonymButton = findAll(view, JButton::class.java).firstOrNull { it.text == "vixen" }
        assertNotNull(synonymButton, "a synonym is still a button, not a label")

        onEdt { synonymButton.doClick() }

        assertEquals(listOf("vixen"), clicked, "and clicking it still looks the word up")
    }

    @Test
    fun `unchanged entries are not rebuilt and changed ones are`() {
        val view = view()
        val first = definitionArea(view)

        onEdt { view.render(entries = listOf(entry()), synonymsLabel = "Synonyms") }

        assertSame(first, definitionArea(view), "the same entries left the same components in place")

        onEdt {
            view.render(
                entries = listOf(entry(definitionText = "a different sense entirely")),
                synonymsLabel = "Synonyms",
            )
        }

        val rebuilt = definitionArea(view, text = "a different sense entirely")
        assertNotSame(first, rebuilt, "new entries rebuild the list")
        assertNull(
            rebuilt.selectedText,
            "and the new area carries no selection, rather than the old range applied to new text"
        )
    }

    @Test
    fun `an empty entry list renders no text surfaces at all`() {
        val view = DictionaryResultView(blindIconManager())
        onEdt {
            view.render(entries = emptyList(), synonymsLabel = "Synonyms")
            view.setSize(600, 800)
        }

        assertTrue(textAreas(view).isEmpty(), "nothing to select, because nothing is drawn")
    }
}
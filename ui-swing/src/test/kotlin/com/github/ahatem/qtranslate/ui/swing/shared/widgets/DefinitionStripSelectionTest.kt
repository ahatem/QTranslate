package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import javax.swing.JMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The definition beneath a translation shares the common Copy and Select All menu and resets on new text. */
class DefinitionStripSelectionTest {

    private val definition = "a short definition of the word"

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

}

package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Font
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DisplayValueRendererTest {
    private data class Option(val id: String, val label: String)

    private val option = Option("a", "Alpha")

    private fun newList() = JList<Option>().apply {
        background = Color(250, 250, 250)
        foreground = Color(20, 20, 20)
        selectionBackground = Color(10, 90, 200)
        selectionForeground = Color(255, 255, 255)
        font = Font("Dialog", Font.ITALIC, 17)
    }

    private fun renderer(
        icon: (Option?) -> Icon? = { null },
        isDisabled: (Option?) -> Boolean = { false },
        tooltip: (Option?) -> String? = { null }
    ) = DisplayValueRenderer<Option>(
        text = { it?.label.orEmpty() },
        icon = icon,
        isDisabled = isDisabled,
        tooltip = tooltip
    )

    private fun cell(
        renderer: DisplayValueRenderer<Option>,
        list: JList<Option>,
        value: Option? = option,
        index: Int = 0,
        selected: Boolean = false,
        focused: Boolean = false
    ) = renderer.getListCellRendererComponent(list, value, index, selected, focused) as JLabel

    @Test
    fun `closed value renders as plain text without selection colors`() = onEdt {
        val list = newList()
        val row = cell(renderer(), list, index = -1, selected = false)
        assertEquals("Alpha", row.text)
        assertNotEquals(list.selectionBackground, row.background)
        assertEquals(list.foreground, row.foreground)
    }

    @Test
    fun `text, icon and tooltip come from the caller per row, and a missing value is empty`() = onEdt {
        val list = newList()
        val glyph = ImageIcon(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))
        val r = renderer(
            icon = { if (it?.id == "a") glyph else null },
            tooltip = { if (it?.id == "a") "why" else null },
        )
        val row = cell(r, list)
        assertEquals("Alpha", row.text)
        assertSame(glyph, row.icon)
        assertEquals("why", row.toolTipText)

        val other = cell(r, list, Option("b", "Beta"))
        assertNull(other.icon)
        assertNull(other.toolTipText)
        assertEquals("", cell(r, list, value = null).text)
    }

    @Test
    fun `disabled row is dimmed even while selected and keeps the selection background`() = onEdt {
        val dim = Color(120, 121, 122)
        val previous = UIManager.getColor("Label.disabledForeground")
        UIManager.put("Label.disabledForeground", dim)
        try {
            val list = newList()
            val r = renderer(isDisabled = { it?.id == "a" })
            val selected = cell(r, list, selected = true)
            assertEquals(dim, selected.foreground)
            assertEquals(list.selectionBackground, selected.background)
            assertEquals(dim, cell(r, list, selected = false).foreground)
            assertEquals(list.foreground, cell(r, list, Option("b", "Beta")).foreground)
        } finally {
            UIManager.put("Label.disabledForeground", previous)
        }
    }

    @Test
    fun `orientation follows the list in both directions`() = onEdt {
        val list = newList()
        val r = renderer()
        list.componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
        assertEquals(ComponentOrientation.LEFT_TO_RIGHT, cell(r, list).componentOrientation)
        list.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
        assertEquals(ComponentOrientation.RIGHT_TO_LEFT, cell(r, list).componentOrientation)
    }

}

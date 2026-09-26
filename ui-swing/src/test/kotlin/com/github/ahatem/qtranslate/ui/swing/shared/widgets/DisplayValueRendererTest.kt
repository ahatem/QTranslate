package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DisplayValueRendererTest {
    private data class Option(val id: String, val label: String)

    private val option = Option("a", "Alpha")

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

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
    fun `is a default list cell renderer`() {
        assertTrue(renderer() is DefaultListCellRenderer)
    }

    @Test
    fun `selected row takes the list selection colors`() = onEdt {
        val list = newList()
        val row = cell(renderer(), list, selected = true)
        assertEquals(list.selectionBackground, row.background)
        assertEquals(list.selectionForeground, row.foreground)
        assertTrue(row.isOpaque)
    }

    @Test
    fun `unselected row takes the list colors`() = onEdt {
        val list = newList()
        val row = cell(renderer(), list, selected = false)
        assertEquals(list.background, row.background)
        assertEquals(list.foreground, row.foreground)
    }

    @Test
    fun `focus state is delegated to the native renderer`() = onEdt {
        val list = newList()
        val native = DefaultListCellRenderer()
            .getListCellRendererComponent(list, option, 0, true, true) as JLabel
        val row = cell(renderer(), list, selected = true, focused = true)
        assertEquals(native.border, row.border)
        assertEquals(native.background, row.background)
    }

    @Test
    fun `closed value renders as plain text without selection colors`() = onEdt {
        val list = newList()
        val row = cell(renderer(), list, index = -1, selected = false)
        assertEquals("Alpha", row.text)
        assertNotEquals(list.selectionBackground, row.background)
        assertEquals(list.foreground, row.foreground)
    }

    @Test
    fun `text comes from the caller and a missing value is empty`() = onEdt {
        val list = newList()
        assertEquals("Alpha", cell(renderer(), list).text)
        assertEquals("", cell(renderer(), list, value = null).text)
    }

    @Test
    fun `optional icon is applied and cleared per row`() = onEdt {
        val list = newList()
        val glyph = ImageIcon(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))
        val r = renderer(icon = { if (it?.id == "a") glyph else null })
        assertSame(glyph, cell(r, list, option).icon)
        assertNull(cell(r, list, Option("b", "Beta")).icon)
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
    fun `tooltip follows the value`() = onEdt {
        val list = newList()
        val r = renderer(tooltip = { if (it?.id == "a") "why" else null })
        assertEquals("why", cell(r, list, option).toolTipText)
        assertNull(cell(r, list, Option("b", "Beta")).toolTipText)
    }

    @Test
    fun `list font and enabled state are kept`() = onEdt {
        val list = newList()
        assertEquals(list.font, cell(renderer(), list).font)
        assertTrue(cell(renderer(), list).isEnabled)
        list.isEnabled = false
        assertFalse(cell(renderer(), list).isEnabled)
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

    @Test
    fun `rows follow the active flatlaf theme`() {
        val original = UIManager.getLookAndFeel()
        val rowBackgrounds = mutableListOf<Color>()
        try {
            listOf(FlatDarkLaf(), FlatLightLaf()).forEach { laf ->
                onEdt {
                    UIManager.setLookAndFeel(laf)
                    val list = JList<Option>()
                    val selected = cell(renderer(), list, selected = true)
                    val plain = cell(renderer(), list, selected = false)
                    assertEquals(list.selectionBackground, selected.background)
                    assertEquals(list.selectionForeground, selected.foreground)
                    assertEquals(list.background, plain.background)
                    rowBackgrounds += plain.background
                }
            }
        } finally {
            onEdt { UIManager.setLookAndFeel(original) }
        }
        assertNotEquals(rowBackgrounds[0], rowBackgrounds[1])
    }

    @Test
    fun `renderer paints no colors of its own`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/shared/widgets/DisplayValueRenderer.kt").readText()
        assertFalse(Regex("""\bColor\(""").containsMatchIn(source), "selection and hover colors belong to the look and feel")
        assertFalse(source.contains("selectionBackground"))
        assertFalse(source.contains("background ="))
        assertFalse(source.contains("MouseListener") || source.contains("MouseAdapter"))
    }
}

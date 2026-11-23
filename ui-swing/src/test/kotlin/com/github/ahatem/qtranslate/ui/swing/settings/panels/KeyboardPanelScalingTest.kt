package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class KeyboardPanelScalingTest {
    private val fixture = SettingsPanelFixture()

    @AfterTest
    fun tearDown() {
        UIScale.setZoomFactor(1f)
        fixture.close()
    }

    private fun tableAt(zoom: Float): JTable {
        UIScale.setZoomFactor(zoom)
        val settings = fixture.store()
        val panel = onEdt { KeyboardPanel(settings, fixture.localizer) }
        onEdt { panel.render(settings.state.value) }
        return panel.tableForTest()
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    @Test
    fun `at a large scale the rows still hold a key chip without clipping it`() {
        val table = tableAt(2f)
        onEdt {
            table.setSize(UIScale.scale(600), table.rowHeight * table.rowCount)
            table.doLayout()
        }

        // The list opens with a category heading, which carries no chips.
        val row = (0 until table.rowCount).first { table.model.getValueAt(it, 1) != null }
        val binding = table.model.getValueAt(row, 1)
        val renderer = table.getCellRenderer(row, 1)
        val rendered = onEdt { renderer.getTableCellRendererComponent(table, binding, false, false, row, 1) as Container }
        onEdt {
            rendered.setSize(table.columnModel.getColumn(1).width, table.rowHeight)
            layoutTree(rendered)
        }
        val chips = descendants(rendered).filterIsInstance<JLabel>()
        assertTrue(chips.isNotEmpty())
        chips.forEach { chip ->
            val top = SwingUtilities.convertPoint(chip, 0, 0, rendered).y
            assertTrue(chip.height in 1..table.rowHeight, "chip height ${chip.height} fits the ${table.rowHeight} row")
            assertTrue(top >= 0 && top + chip.height <= table.rowHeight, "chip sits vertically inside the row")
        }
    }
}

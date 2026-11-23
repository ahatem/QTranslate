package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.UIManager
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * A list of rows painted by the application, themed entirely by the look and feel.
 *
 * A `JList` with a custom cell renderer rather than a column of panels: the list brings keyboard
 * navigation, type-ahead, single selection, scroll-to-selection and an accessible table model for
 * free, and FlatLaf draws the selection pill — including its inactive and focused states — from
 * `List.selection*`. Building the same thing out of panels meant re-implementing all of that and
 * hand-painting selection with colours picked by hand, which is how the rows used to be painted.
 *
 * What this class adds on top of a plain `JList`:
 *
 * - **A hover row.** FlatLaf paints hover for trees and lists it knows about; a custom renderer
 *   list needs it drawn behind the rows. The colour is read from `List.selectionInactiveBackground`
 *   at paint time, so it follows the theme and the arc/insets match the selection pill exactly.
 * - **A trailing hotspot.** A row can carry an interactive control at its trailing edge (a switch
 *   to enable that item). The renderer paints it; this class reports clicks that land on it,
 *   measured from the row's trailing edge, so the two cannot drift apart.
 * - **Activation.** A double click, or Enter on a focused row, asks the host to open the row.
 *
 * No colour, font or border here is a literal: everything comes from the look and feel, so a theme
 * switch needs no work beyond the repaint the theme change already triggers.
 *
 * @param trailingHotspot logical width of the interactive control at a row's trailing edge, or 0
 *   for rows without one.
 */
internal class SettingsRowList<E : Any>(
    model: DefaultListModel<E>,
    private val trailingHotspot: Int = 0,
    /** Double click or Enter on a row. */
    private val onRowActivated: (E) -> Unit = {},
    /** A click on the trailing control, with the row and the control's bounds in list coordinates. */
    private val onTrailingPressed: (E, Rectangle) -> Unit = { _, _ -> },
) : JList<E>(model) {

    /** Row the pointer is over, or -1. Drives the hover shape. */
    private var hoveredRow: Int = -1

    init {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        isOpaque = false
        clearBorder()
        // The pill FlatLaf draws for a tree row, so the two lists in this dialog agree.
        putClientProperty(
            FlatClientProperties.STYLE,
            "selectionArc: 8; selectionInsets: 2,4,2,4"
        )
        // Rows may be shorter or taller than the theme's default row height (a two-line row is
        // not), and a fixed height would clip them.
        fixedCellHeight = -1

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = locationToIndex(e.point)
                // locationToIndex answers with the nearest row even for a point in the empty space
                // below the last one.
                val inside = row >= 0 && getCellBounds(row, row)?.contains(e.point) == true
                val next = if (inside) row else -1
                if (next != hoveredRow) {
                    hoveredRow = next
                    repaint()
                }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hoveredRow != -1) {
                    hoveredRow = -1
                    repaint()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                val row = rowAt(e.point) ?: return
                if (trailingHotspot > 0) {
                    val control = trailingControlBounds(row) ?: return
                    if (control.contains(e.point)) {
                        onTrailingPressed(getModel().getElementAt(row), control)
                        return
                    }
                }
                if (e.clickCount == 2) onRowActivated(getModel().getElementAt(row))
            }
        })
        addKeyListener(object : KeyListener {
            override fun keyPressed(e: KeyEvent) {
                val row = selectedIndex
                if (row < 0) return
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> onRowActivated(getModel().getElementAt(row))
                    KeyEvent.VK_SPACE -> if (trailingHotspot > 0) {
                        trailingControlBounds(row)?.let {
                            onTrailingPressed(getModel().getElementAt(row), it)
                        }
                    }
                }
            }

            override fun keyTyped(e: KeyEvent) = Unit
            override fun keyReleased(e: KeyEvent) = Unit
        })
    }

    /** The row under [point], or null for the empty space below the last one. */
    private fun rowAt(point: java.awt.Point): Int? {
        val row = locationToIndex(point)
        if (row < 0) return null
        return if (getCellBounds(row, row)?.contains(point) == true) row else null
    }

    /**
     * Where the trailing control sits in [row], in list coordinates.
     *
     * Derived from the row's own bounds and the width the renderer reports, so it stays right at
     * any scale, in either layout direction, and when the divider moves.
     */
    private fun trailingControlBounds(row: Int): Rectangle? {
        val cell = getCellBounds(row, row) ?: return null
        val width = UIScale.scale(trailingHotspot)
        if (width <= 0) return null
        return if (componentOrientation.isLeftToRight) {
            Rectangle(cell.x + cell.width - width, cell.y, width, cell.height)
        } else {
            Rectangle(cell.x, cell.y, width, cell.height)
        }
    }

    /**
     * Paints the selected and hovered rows behind their content.
     *
     * A `JList` leaves selection to its renderer, and this list's renderer is a non-opaque panel —
     * so nothing was painted at all: the selected row was indistinguishable from its neighbours,
     * which is how it was reported. Painting it here is also what makes the pill the same shape as
     * the hover, and what allows the accent bar below: with the themes this application ships, the
     * selection background alone is a shade, and a shade is not an answer to "which row am I on".
     *
     * Every colour is read from the look and feel at paint time, so it follows the theme, the focus
     * state and a custom accent.
     */
    override fun paintComponent(g: Graphics) {
        paintRow(g, selectedIndex, selected = true)
        val hover = hoveredRow
        if (hover >= 0 && !isSelectedIndex(hover)) paintRow(g, hover, selected = false)
        super.paintComponent(g)
    }

    private fun paintRow(g: Graphics, row: Int, selected: Boolean) {
        if (row < 0) return
        val bounds = getCellBounds(row, row) ?: return

        val fill = when {
            !selected -> UIManager.getColor("List.selectionInactiveBackground")
            hasFocus() -> UIManager.getColor("List.selectionBackground")
            else -> UIManager.getColor("List.selectionInactiveBackground")
                ?: UIManager.getColor("List.selectionBackground")
        } ?: UIManager.getColor("Component.borderColor") ?: return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // The insets FlatLaf uses for the selection pill, so hover and selection are the same
            // shape rather than two shapes a pixel apart.
            val insetX = UIScale.scale(4)
            val insetY = UIScale.scale(2)
            val arc = UIScale.scale(8)
            val x = bounds.x + insetX
            val y = bounds.y + insetY
            val width = (bounds.width - insetX * 2).coerceAtLeast(0)
            val height = (bounds.height - insetY * 2).coerceAtLeast(0)

            g2.color = fill
            g2.fillRoundRect(x, y, width, height, arc, arc)

            if (selected) {
                val accent = UIManager.getColor("Component.accentColor") ?: return
                // In the gutter beside the pill, not inside it: square ends, the pill's own height.
                // A rounded bar next to a rounded pill reads as a second pill, and an accent bar
                // drawn inside the pill would put its square corners across the pill's rounded ones.
                // This is the marker a comparison result carries, so the two read as one idea.
                val bar = UIScale.scale(3)
                val barX = if (componentOrientation.isLeftToRight) {
                    bounds.x + UIScale.scale(1)
                } else {
                    bounds.x + bounds.width - bar - UIScale.scale(1)
                }
                g2.color = accent
                g2.fillRect(barX, y, bar, height)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        /**
         * Wraps this list in a scroll pane with the settings dialog's chrome: no border, no
         * horizontal scrolling, and a unit increment that matches the other scrolling areas.
         */
        fun <E : Any> scrollOf(list: SettingsRowList<E>): JScrollPane = JScrollPane(list).apply {
            clearBorder()
            viewportBorder = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = 16
            viewport.isOpaque = false
            // The rows carry their own inset, so the viewport must not add another.
            border = BorderFactory.createEmptyBorder()
        }

        /** The colour a row's secondary line uses, from the theme rather than from a literal. */
        fun secondaryForeground(): Color =
            UIManager.getColor("Label.disabledForeground") ?: UIManager.getColor("Label.foreground")
    }
}

package com.github.ahatem.qtranslate.ui.swing.main.widgets

import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.util.UIScale
import java.awt.Graphics
import java.awt.Insets
import javax.swing.Icon
import javax.swing.JPopupMenu
import javax.swing.UIManager

/**
 * A toolbar button that can draw a trailing chevron, the way a chip that opens a menu or popup
 * shows it.
 *
 * The chevron is drawn by the button rather than laid out beside it, so there is one hover state,
 * one focus ring and one click target however wide the row is. It sits at the trailing edge on
 * whichever side that is, and its room is added to the insets rather than the margin: the room is
 * already in pixels, and FlatLaf's border scales the margin.
 *
 * Shared by the translator picker and the chips that stand in for a hidden region, so they cannot
 * drift apart in how the chevron is placed or mirrored.
 */
open class ChevronButton : FlatButton() {

    /** Whether the chevron is drawn; off, the button is an ordinary toolbar button. */
    var showChevron: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    protected val chevronIcon: Icon get() = UIManager.getIcon("Table.descendingSortIcon")

    override fun getInsets(): Insets = withChevronRoom(super.getInsets())

    override fun getInsets(insets: Insets?): Insets = withChevronRoom(super.getInsets(insets))

    private fun withChevronRoom(insets: Insets): Insets {
        if (!showChevron) return insets
        val room = chevronIcon.iconWidth + UIScale.scale(CHEVRON_GAP)
        if (componentOrientation.isLeftToRight) insets.right += room else insets.left += room
        return insets
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!showChevron) return
        val chevron = chevronIcon
        val y = (height - chevron.iconHeight) / 2
        val inset = UIScale.scale(CHEVRON_GAP) / 2 + (UIManager.getInsets("Button.toolbar.margin")?.right ?: 3)
        val x = if (componentOrientation.isLeftToRight) width - inset - chevron.iconWidth else inset
        chevron.paintIcon(this, g, x, y)
    }

    /**
     * Opens [popup] under the button, lined up with its leading edge: the left edge in a
     * left-to-right interface, the right edge in a right-to-left one.
     */
    protected fun showPopupBelow(popup: JPopupMenu) {
        popup.applyComponentOrientation(componentOrientation)
        val x = if (componentOrientation.isLeftToRight) 0 else width - popup.preferredSize.width
        popup.show(this, x, height)
    }

    private companion object {
        const val CHEVRON_GAP = 6
    }
}

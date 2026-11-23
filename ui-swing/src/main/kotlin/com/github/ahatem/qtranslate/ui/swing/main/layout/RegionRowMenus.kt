package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import com.github.ahatem.qtranslate.ui.swing.main.menus.RegionMenu
import com.github.ahatem.qtranslate.ui.swing.main.menus.RegionMenuStrings
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator

/**
 * Makes each chrome row of the real window the place to arrange it: a right-click, or Shift+F10 or
 * the menu key on a focused control, opens the same [RegionMenu] the View menu offers.
 *
 * The popup belongs to the row's container, and every control inside the row inherits it
 * ([JComponent.setInheritsPopupMenu]), unless the control has a context menu of its own. A row that
 * holds one region shows that region's menu; a row shared by several lists one submenu per region.
 * Choices apply to the window at once, as the View menu's toggles do.
 */
class RegionRowMenus(
    /** What the window is now; null before the first render. Read each time a popup opens. */
    private val current: () -> Current?,
    private val strings: () -> RegionMenuStrings,
    private val onChange: (WindowLayout) -> Unit,
    private val onSaveProfile: () -> Unit,
) {
    /** The arrangement a popup is built for. */
    data class Current(val layout: WindowLayout, val effectivePanes: PaneLayout, val isRtl: Boolean)

    /** The rows that carry a popup, top to bottom within each slot. */
    var rows: List<RegionRow> = emptyList()
        private set

    /** The popups, in the order of the rows they belong to. */
    val popups: List<RowPopup> get() = rows.mapNotNull { it.container.componentPopupMenu as? RowPopup }

    /** Attaches a popup to every row of a new arrangement. */
    fun install(arranged: ArrangedLayout) {
        rows = arranged.rows
        rows.forEach { row -> row.container.componentPopupMenu = RowPopup(row) }
        refreshInheritance()
    }

    /**
     * Lets every control of every row share its row's popup. Controls that were created after the
     * row was built (a tab per service, say) are picked up on the next call, and one that has a
     * popup of its own is left alone.
     */
    fun refreshInheritance() {
        rows.forEach { row -> row.container.components.forEach { inherit(it) } }
    }

    private fun inherit(component: Component) {
        if (component is JComponent && !component.inheritsPopupMenu && component.componentPopupMenu == null) {
            component.inheritsPopupMenu = true
        }
        if (component is Container) component.components.forEach { inherit(it) }
    }

    /** The popup of one row, rebuilt from the current layout each time it opens. */
    inner class RowPopup(private val row: RegionRow) : JPopupMenu() {

        /** Fills the popup for the window as it is now. */
        fun refresh() {
            removeAll()
            val now = current() ?: return
            val words = strings()
            fun menuOf(region: WindowRegion, save: (() -> Unit)?) =
                RegionMenu(region, now.layout, words, onChange, now.effectivePanes, save)

            if (row.regions.size == 1) {
                menuOf(row.regions.single(), onSaveProfile).items().forEach { add(it) }
            } else {
                row.regions.forEach { add(menuOf(it, null).menu()) }
                add(JSeparator())
                add(JMenuItem(words.saveProfile).apply {
                    name = RegionMenu.SAVE_PROFILE
                    addActionListener { onSaveProfile() }
                })
            }
            applyComponentOrientation(
                if (now.isRtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
            )
        }

        // The size used to place the popup on screen is taken before it becomes visible, so the
        // items have to be current by then.
        override fun show(invoker: Component?, x: Int, y: Int) {
            refresh()
            super.show(invoker, x, y)
        }
    }
}

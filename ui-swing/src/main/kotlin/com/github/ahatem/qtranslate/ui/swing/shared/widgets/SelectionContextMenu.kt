package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.JTextComponent

/** Resolves a context-menu item label from a key such as `copy` or `select_all`. */
internal typealias ContextMenuLabels = (String) -> String

/**
 * Copy and Select All for read-only selectable text, in place of the stock menu, which offers Cut
 * and Paste on something that cannot be typed into.
 *
 * Both act on the component's own selection, which is what keeps one surface's copy separate from
 * another's. [copyAction] receives the component it was installed on.
 */
internal class SelectionContextMenu(
    private val component: JTextComponent,
    private val copyAction: (JTextComponent) -> Unit = { it.copy() },
) {

    /** Overrides the item labels; null keeps this widget's English defaults. */
    var labels: ContextMenuLabels? = null

    val copyItem = JMenuItem("Copy")
    val selectAllItem = JMenuItem("Select All")

    val menu = JPopupMenu()

    init {
        copyItem.addActionListener { copyAction(component) }
        selectAllItem.addActionListener { component.selectAll() }

        menu.add(copyItem)
        menu.add(selectAllItem)

        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = refresh()
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
        })

        component.componentPopupMenu = menu
        component.putClientProperty(INSTALLER_PROPERTY, this)
    }

    /**
     * Re-reads the selection and the labels; runs each time the menu opens, so a language change
     * reaches menus already on screen. Neither item is offered where it would do nothing.
     */
    internal fun refresh() {
        copyItem.isEnabled = component.selectedText != null
        selectAllItem.isEnabled = component.text.isNotEmpty()

        labels?.let { get ->
            copyItem.text = get(COPY_KEY)
            selectAllItem.text = get(SELECT_ALL_KEY)
        }
    }

    companion object {
        const val COPY_KEY = "copy"
        const val SELECT_ALL_KEY = "select_all"
        const val INSTALLER_PROPERTY = "qtranslate.selectionContextMenu"
    }
}

/** The menu installed on [this], if any. */
internal fun JComponent.selectionContextMenu(): SelectionContextMenu? =
    getClientProperty(SelectionContextMenu.INSTALLER_PROPERTY) as? SelectionContextMenu

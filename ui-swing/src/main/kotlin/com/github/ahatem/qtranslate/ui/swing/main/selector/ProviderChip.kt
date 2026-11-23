package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * The translation service, as one chip: the active service's icon and name, opening a list of the
 * others.
 *
 * It stands in for the service selector when that is not drawn. Choosing a service reports it
 * through the same callback the selector uses, [onServiceSelected], so whatever the selector does
 * on a choice (updating the preset, translating again) happens here too and the two cannot differ.
 *
 * The face, chevron and keyboard behaviour are the translator picker's; only the popup differs: a
 * list drawn by [ServiceListCellRenderer], the renderer every other service list uses, with the
 * active service marked.
 */
class ProviderChip(
    private val iconManager: IconManager,
    private val onServiceSelected: (ServiceRole, String) -> Unit
) : TranslatorPopupButton(
    iconManager = iconManager,
    onTranslatorSelected = { serviceId -> onServiceSelected(ServiceRole.TRANSLATOR, serviceId) },
    textMode = true
) {
    override fun render(state: TranslatorSelectorState) {
        noSelectionText = state.strings.noTranslator
        actionTooltip = state.strings.providerChipTooltip
        super.render(state)
    }

    override fun showPopupMenu() {
        val menu = buildPopup() ?: return
        showPopupBelow(menu)
    }

    /** The popup the chip would open, for tests; null when there is nothing to choose from. */
    internal fun popupForTest(): JPopupMenu? = buildPopup()

    private fun buildPopup(): JPopupMenu? {
        val state = currentState ?: return null
        val services = state.availableTranslators
        if (services.isEmpty()) return null

        val menu = JPopupMenu()
        val list = JList(DefaultListModel<ServiceInfo>().apply { services.forEach(::addElement) }).apply {
            name = LIST_NAME
            cellRenderer = ServiceListCellRenderer(iconManager)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = services.size.coerceAtMost(MAX_VISIBLE_ROWS)
            selectedIndex = services.indexOfFirst { it.id == state.selectedTranslatorId }
            // Enter chooses the highlighted row, and a click chooses the row under it. Both go
            // through the same choice so the keyboard cannot do something the mouse does not.
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), CHOOSE)
            actionMap.put(CHOOSE, object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    selectedValue?.let { choose(menu, it) }
                }
            })
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    val row = locationToIndex(e.point)
                    if (row >= 0 && getCellBounds(row, row)?.contains(e.point) == true) {
                        choose(menu, model.getElementAt(row))
                    }
                }
            })
        }
        menu.add(JScrollPane(list).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(
                list.preferredSize.width + UIScale.scale(SCROLLBAR_ROOM),
                list.preferredSize.height.coerceAtMost(UIScale.scale(MAX_HEIGHT))
            )
        })
        menu.isFocusable = true
        return menu
    }

    private fun choose(menu: JPopupMenu, service: ServiceInfo) {
        menu.isVisible = false
        onServiceSelected(ServiceRole.TRANSLATOR, service.id)
    }

    internal companion object {
        const val LIST_NAME = "providerChipList"
        private const val CHOOSE = "chooseService"
        private const val MAX_VISIBLE_ROWS = 10
        private const val SCROLLBAR_ROOM = 24
        private const val MAX_HEIGHT = 320
    }
}

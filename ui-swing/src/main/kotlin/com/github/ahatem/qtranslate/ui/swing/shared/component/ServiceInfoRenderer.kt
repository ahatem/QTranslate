package com.github.ahatem.qtranslate.ui.swing.shared.component

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * Renders a service in a dropdown: its icon, then its name.
 *
 * Shared because it was not. The translator picker drew icons and the three dictionary pickers
 * drew bare text, from the same [ServiceInfo] carrying the same `iconPath` — the difference was
 * that nobody had written the renderer twice, not that dictionaries were meant to look plainer.
 *
 * Icons come from the plugin that provides the service, so they load through the plugin's own
 * class loader rather than the application's.
 */
class ServiceInfoRenderer(
    private val iconManager: IconManager,
    private val iconSize: Int = 16
) : DefaultListCellRenderer() {

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

        val service = value as? ServiceInfo
        text = service?.name.orEmpty()
        icon = service?.iconPath?.let { iconManager.getIcon(service.id, it, iconSize, iconSize) }
        iconTextGap = 6
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        return this
    }
}

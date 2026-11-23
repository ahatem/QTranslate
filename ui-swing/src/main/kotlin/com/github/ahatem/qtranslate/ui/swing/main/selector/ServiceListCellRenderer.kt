package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JList

/** The size every service icon is drawn at, in the strip, the menus and the lists. */
internal const val SERVICE_ICON_SIZE = 16

/** The icon for [service], or null when it has none. */
internal fun IconManager.serviceIcon(service: ServiceInfo): Icon? =
    service.iconPath?.let { getIcon(service.id, it, SERVICE_ICON_SIZE, SERVICE_ICON_SIZE) }

/**
 * How a service looks in any list that offers one: its icon and its name, in the look and feel's
 * own list-row styling.
 *
 * Shared by the selector's per-role combo boxes and the provider chip's popup, so a service is
 * drawn the same wherever it can be chosen.
 */
internal class ServiceListCellRenderer(private val iconManager: IconManager) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component =
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
            val service = value as? ServiceInfo
            text = service?.name.orEmpty()
            icon = service?.let(iconManager::serviceIcon)
        }
}

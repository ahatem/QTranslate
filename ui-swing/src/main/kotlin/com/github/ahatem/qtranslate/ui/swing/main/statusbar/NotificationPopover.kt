package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.border.MatteBorder

/**
 * A popover panel anchored to the notification bell that lists recent [NotificationEntry] items.
 * Shown as a lightweight popup so it closes automatically when focus leaves it.
 */
class NotificationPopover(
    private val emptyLabel: String,
    private val clearAllLabel: String,
    private val onCleared: () -> Unit = {},
) {

    data class NotificationEntry(
        val message: String,
        val type: NotificationType,
    )

    private val listModel = DefaultListModel<NotificationEntry>()
    private val list = JList(listModel)
    private val popup = JPopupMenu()

    init {
        list.cellRenderer = NotificationCellRenderer()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.isOpaque = false

        val scrollPane = JScrollPane(list).apply {
            preferredSize = Dimension(360, 240)
            border = BorderFactory.createEmptyBorder()
        }

        val clearButton = JButton(clearAllLabel).apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            addActionListener { clearAll() }
        }

        val footer = JPanel(BorderLayout()).apply {
            border = MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor"))
            add(clearButton, BorderLayout.EAST)
        }

        val content = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(scrollPane, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }

        popup.add(content)
        popup.isFocusable = true
    }

    val count: Int get() = if (hasNotifications()) listModel.size else 0

    fun addNotification(entry: NotificationEntry) {
        listModel.add(0, entry)
        if (listModel.size > 50) listModel.removeElementAt(listModel.size - 1)
    }

    fun show(invoker: Component) {
        if (listModel.isEmpty) listModel.addElement(NotificationEntry(emptyLabel, NotificationType.INFO))
        popup.show(invoker, 0, -popup.preferredSize.height)
    }

    fun hasNotifications(): Boolean = listModel.size > 0 && listModel[0].message != emptyLabel

    private fun clearAll() {
        listModel.clear()
        popup.isVisible = false
        onCleared()
    }

    private inner class NotificationCellRenderer : ListCellRenderer<NotificationEntry> {
        override fun getListCellRendererComponent(
            list: JList<out NotificationEntry>,
            value: NotificationEntry,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val label = JLabel(value.message).apply {
                putClientProperty(FlatClientProperties.STYLE_CLASS, "small")
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background
                foreground = typeColor(value.type)
                iconTextGap = 6
            }
            return label
        }

        private fun typeColor(type: NotificationType): Color = when (type) {
            NotificationType.SUCCESS -> UIManager.getColor("Actions.Green") ?: Color(0x59A869)
            NotificationType.WARNING -> UIManager.getColor("Actions.Yellow") ?: Color(0xE2A53A)
            NotificationType.ERROR -> UIManager.getColor("Actions.Red") ?: Color(0xE05555)
            NotificationType.INFO -> UIManager.getColor("Label.foreground") ?: Color.BLACK
        }
    }
}

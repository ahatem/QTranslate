package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import javax.swing.plaf.basic.BasicComboBoxRenderer

class TranslatorComboBox(
    private val iconManager: IconManager,
    private val onTranslatorSelected: (serviceId: String) -> Unit
) : JPanel(BorderLayout()), Renderable<TranslatorSelectorState> {

    private companion object {
        const val ICON_SIZE = 16
    }

    private val comboBox = JComboBox<TranslatorItem>().apply {
        renderer = TranslatorRenderer()
        isFocusable = false

        addActionListener {
            val selected = selectedItem as? TranslatorItem
            selected?.let { onTranslatorSelected(it.service.id) }
        }
    }

    init {
        isOpaque = false
        add(comboBox, BorderLayout.CENTER)
    }

    override fun render(state: TranslatorSelectorState) {
        val currentItems = (0 until comboBox.itemCount).map { comboBox.getItemAt(it) }
        val newServices = state.availableTranslators

        // Rebuild if services changed
        if (currentItems.map { it.service.id } != newServices.map { it.id }) {
            rebuildItems(newServices)
        }

        // Select current translator
        val selectedItem = state.selectedTranslatorId?.let { id ->
            (0 until comboBox.itemCount)
                .map { comboBox.getItemAt(it) }
                .find { it.service.id == id }
        }

        if (selectedItem != null && comboBox.selectedItem != selectedItem) {
            comboBox.selectedItem = selectedItem
        }

        // Update enabled state
        comboBox.isEnabled = !state.isLoading
    }

    private fun rebuildItems(services: List<ServiceInfo>) {
        comboBox.removeAllItems()
        services.forEach { service ->
            val icon = service.iconPath?.let { path ->
                iconManager.getIcon(service.id, path, ICON_SIZE, ICON_SIZE)
            }
            comboBox.addItem(TranslatorItem(service, icon))
        }
    }

    /**
     * Data class to hold service info + icon
     */
    private data class TranslatorItem(
        val service: ServiceInfo,
        val icon: Icon?
    )

    /**
     * Custom renderer to show icon + name in a clean, minimal way
     */
    private inner class TranslatorRenderer : BasicComboBoxRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            val item = value as? TranslatorItem
            if (item != null) {
                icon = item.icon
                text = item.service.name
                // Use a consistent border/padding provided by the Look and Feel
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            }

            return this
        }
    }
}
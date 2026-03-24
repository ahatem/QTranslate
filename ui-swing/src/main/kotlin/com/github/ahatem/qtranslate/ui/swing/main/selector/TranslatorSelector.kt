package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.ComponentOrientation
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JToggleButton

class TranslatorSelector(
    private val iconManager: IconManager,
    private val onTranslatorSelected: (serviceId: String) -> Unit
) : JPanel(), Renderable<TranslatorSelectorState> {

    private var buttonGroup = ButtonGroup()
    private val serviceButtons = mutableMapOf<String, JToggleButton>()

    companion object {
        private const val ICON_SIZE = 16
    }

    init {
        layout = FlowLayout(FlowLayout.LEADING, 2, 0)
        isOpaque = false
    }

    override fun render(state: TranslatorSelectorState) {
        val currentServiceIds = serviceButtons.keys
        val newServiceIds = state.availableTranslators.map { it.id }.toSet()

        if (currentServiceIds != newServiceIds) {
            rebuildButtons(state.availableTranslators)
        }

        selectButton(state.selectedTranslatorId)
        updateEnabledState(!state.isLoading)
    }

    override fun applyComponentOrientation(o: ComponentOrientation) {
        super.applyComponentOrientation(o)
        revalidate()
    }

    private fun selectButton(serviceId: String?) {
        val buttonToSelect = serviceId?.let { serviceButtons[it] }
        when {
            buttonToSelect != null && !buttonToSelect.isSelected -> buttonToSelect.isSelected = true
            serviceId == null -> buttonGroup.clearSelection()
        }
    }

    private fun updateEnabledState(enabled: Boolean) {
        serviceButtons.values.forEach { it.isEnabled = enabled }
    }

    private fun rebuildButtons(services: List<ServiceInfo>) {
        removeAll()
        serviceButtons.clear()
        buttonGroup = ButtonGroup()

        services.forEach { service ->
            val button = createTranslatorButton(service)
            serviceButtons[service.id] = button
            add(button)
            buttonGroup.add(button)
        }

        revalidate()
        repaint()
    }

    private fun createTranslatorButton(service: ServiceInfo): JToggleButton {
        return JToggleButton().apply {
            icon = service.iconPath?.let { path ->
                iconManager.getIcon(service.id, path, ICON_SIZE, ICON_SIZE)
            }
            text = service.name
            toolTipText = service.name
            isFocusable = false
            isOpaque = false

            putClientProperty("JButton.buttonType", "toolBarButton")
            margin = Insets(4, 6, 4, 6)

            addActionListener { onTranslatorSelected(service.id) }
        }
    }
}
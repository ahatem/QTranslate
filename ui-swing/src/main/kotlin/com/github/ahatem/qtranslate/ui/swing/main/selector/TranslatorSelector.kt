package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class TranslatorSelector(
    private val iconManager: IconManager,
    private val onServiceSelected: (ServiceType, String) -> Unit,
    private val onConfigureService: (String) -> Unit = {}
) : JPanel(CardLayout()), Renderable<TranslatorSelectorState> {
    private companion object { const val CLASSIC = "classic"; const val ENHANCED = "enhanced"; const val ICON_SIZE = 16 }

    private var state = TranslatorSelectorState(emptyList(), null, false)
    private val classicButtons = JPanel(FlowLayout(FlowLayout.LEADING, 2, 0)).apply { isOpaque = false }
    private val classicScroll = JScrollPane(classicButtons).apply {
        border = null; isOpaque = false; viewport.isOpaque = false
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        horizontalScrollBar.unitIncrement = 24
        mouseWheelListeners.forEach(::removeMouseWheelListener)
        addMouseWheelListener { e -> scrollClassic(e.wheelRotation * horizontalScrollBar.unitIncrement) }
    }
    private val scrollBack = createScrollButton("icons/lucide/arrow-left.svg", -96, "Previous services")
    private val scrollForward = createScrollButton("icons/lucide/arrow-right.svg", 96, "More services")
    private val configureActive = JButton(iconManager.getIcon("icons/lucide/settings.svg", 16, 16)).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        toolTipText = "Configure active translation service"; isFocusable = false
        addActionListener { state.selectedTranslatorId?.let(onConfigureService) }
    }
    private val classicControls = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply {
        isOpaque = false; add(scrollForward); add(configureActive)
    }
    private val classic = JPanel(BorderLayout(2, 0)).apply {
        isOpaque = false; add(scrollBack, BorderLayout.LINE_START); add(classicScroll); add(classicControls, BorderLayout.LINE_END)
    }
    private val enhanced = JPanel(GridLayout(1, 0, 8, 0)).apply { isOpaque = false }

    init {
        isOpaque = false; add(classic, CLASSIC); add(enhanced, ENHANCED)
        classicScroll.viewport.addChangeListener { updateClassicOverflowControls() }
    }

    override fun render(state: TranslatorSelectorState) {
        this.state = state
        if (state.style == ServiceSelectorStyle.CLASSIC) rebuildClassic() else rebuildEnhanced()
        (layout as CardLayout).show(this, if (state.style == ServiceSelectorStyle.CLASSIC) CLASSIC else ENHANCED)
        revalidate(); repaint()
    }

    private fun rebuildClassic() {
        classicButtons.removeAll()
        val group = ButtonGroup()
        var selectedButton: JToggleButton? = null
        state.availableTranslators.forEach { service ->
            val serviceIcon = loadIcon(service)
            val button = JToggleButton().apply {
                icon = if (state.appearance == ServiceSelectorAppearance.TEXT_ONLY) null else serviceIcon
                text = when (state.appearance) {
                    ServiceSelectorAppearance.ICONS_ONLY -> if (serviceIcon == null) service.name else null
                    else -> service.name
                }
                toolTipText = service.name; isSelected = service.id == state.selectedTranslatorId
                isEnabled = !state.isLoading; isFocusable = false; isOpaque = false
                putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
                margin = Insets(4, 6, 4, 6)
                addActionListener { onServiceSelected(ServiceType.TRANSLATOR, service.id) }
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) onConfigureService(service.id) }
                })
            }
            if (button.isSelected) selectedButton = button
            group.add(button); classicButtons.add(button)
        }
        configureActive.isEnabled = !state.isLoading && state.selectedTranslatorId != null
        classicButtons.revalidate()
        SwingUtilities.invokeLater {
            selectedButton?.let { it.scrollRectToVisible(it.bounds) }
            updateClassicOverflowControls()
        }
    }

    private fun rebuildEnhanced() {
        enhanced.removeAll()
        listOf(ServiceType.TRANSLATOR to "Translate", ServiceType.DICTIONARY to "Dictionary", ServiceType.TTS to "Voice")
            .forEach { (type, label) ->
                val services = state.availableServices.filter { it.type == type }
                if (services.isNotEmpty()) enhanced.add(createServicePicker(type, label, services))
            }
    }

    private fun createServicePicker(type: ServiceType, label: String, services: List<ServiceInfo>): JComponent {
        val combo = JComboBox(services.toTypedArray()).apply {
            renderer = ServiceRenderer(); selectedItem = services.find { it.id == state.selectedServices[type] } ?: services.first()
            isEnabled = !state.isLoading; toolTipText = "Select $label service"
            minimumSize = Dimension(0, preferredSize.height)
            addActionListener { (selectedItem as? ServiceInfo)?.let { onServiceSelected(type, it.id) } }
        }
        val gear = JButton(iconManager.getIcon("icons/lucide/settings.svg", 16, 16)).apply {
            putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton"); toolTipText = "Configure selected $label service"; isFocusable = false
            addActionListener { (combo.selectedItem as? ServiceInfo)?.let { onConfigureService(it.id) } }
        }
        return JPanel(BorderLayout(4, 2)).apply {
            isOpaque = false
            minimumSize = Dimension(0, preferredSize.height)
            add(JLabel(label), BorderLayout.PAGE_START)
            add(combo)
            add(gear, BorderLayout.LINE_END)
        }
    }

    private fun createScrollButton(iconPath: String, amount: Int, tooltip: String) =
        JButton(iconManager.getIcon(iconPath, 16, 16)).apply {
            putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
            toolTipText = tooltip; isFocusable = false
            addActionListener { scrollClassic(amount) }
        }

    private fun scrollClassic(amount: Int) {
        val bar = classicScroll.horizontalScrollBar
        bar.value = (bar.value + amount).coerceIn(bar.minimum, bar.maximum - bar.visibleAmount)
        updateClassicOverflowControls()
    }

    private fun updateClassicOverflowControls() {
        val bar = classicScroll.horizontalScrollBar
        val overflowing = classicButtons.preferredSize.width > classicScroll.viewport.extentSize.width
        val visibilityChanged = scrollBack.isVisible != overflowing || scrollForward.isVisible != overflowing
        scrollBack.isVisible = overflowing
        scrollForward.isVisible = overflowing
        scrollBack.isEnabled = overflowing && bar.value > bar.minimum
        scrollForward.isEnabled = overflowing && bar.value + bar.visibleAmount < bar.maximum
        if (visibilityChanged) classic.revalidate()
    }

    private fun loadIcon(service: ServiceInfo): Icon? = service.iconPath?.let { iconManager.getIcon(service.id, it, ICON_SIZE, ICON_SIZE) }

    private inner class ServiceRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component =
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                val service = value as? ServiceInfo; text = service?.name.orEmpty(); icon = service?.let(::loadIcon)
            }
    }
}

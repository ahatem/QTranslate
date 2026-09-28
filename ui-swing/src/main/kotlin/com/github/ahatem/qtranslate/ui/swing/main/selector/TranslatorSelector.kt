package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

class TranslatorSelector(
    private val iconManager: IconManager,
    private val onServiceSelected: (ServiceRole, String) -> Unit,
    private val onConfigureService: (String) -> Unit = {}
) : JPanel(CardLayout()), Renderable<TranslatorSelectorState> {
    private companion object { const val CLASSIC = "classic"; const val ENHANCED = "enhanced"; const val ICON_SIZE = 16 }

    private var state = TranslatorSelectorState(emptyList(), null, false)
    private val activeSlot = JPanel(BorderLayout()).apply { isOpaque = false }
    private val otherButtons = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(2), 0)).apply { isOpaque = false }
    private var activeButton: JToggleButton? = null
    private var remainingButtons: List<JToggleButton> = emptyList()
    private val overflowMenu = JButton("More ▾").apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        margin = compactButtonMargin()
        toolTipText = "All services"
        addActionListener { showOverflowMenu(this) }
    }
    private val configureActive = JButton(iconManager.getIcon(Icons.SETTINGS, 16, 16)).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        margin = compactButtonMargin()
        toolTipText = "Configure active translation service"
        addActionListener { state.selectedTranslatorId?.let(onConfigureService) }
    }
    private val classicControls = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply {
        isOpaque = false; add(overflowMenu); add(configureActive)
    }
    private val classic = JPanel(BorderLayout(UIScale.scale(2), 0)).apply {
        isOpaque = false
        add(activeSlot, BorderLayout.LINE_START)
        add(otherButtons, BorderLayout.CENTER)
        add(classicControls, BorderLayout.LINE_END)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitClassicButtons()
        })
    }
    private val enhanced = JPanel(GridLayout(1, 0, 8, 0)).apply { isOpaque = false }

    init {
        isOpaque = false; add(classic, CLASSIC); add(enhanced, ENHANCED)
    }

    override fun render(state: TranslatorSelectorState) {
        this.state = state
        if (state.style == ServiceSelectorStyle.CLASSIC) rebuildClassic() else rebuildEnhanced()
        (layout as CardLayout).show(this, if (state.style == ServiceSelectorStyle.CLASSIC) CLASSIC else ENHANCED)
        revalidate(); repaint()
    }

    private fun rebuildClassic() {
        val services = state.availableTranslators
        val selected = services.find { it.id == state.selectedTranslatorId } ?: services.firstOrNull()
        val group = ButtonGroup()
        val buttons = services.associate { service ->
            val serviceIcon = loadIcon(service)
            service.id to JToggleButton().apply {
                icon = if (state.appearance == ServiceSelectorAppearance.TEXT_ONLY) null else serviceIcon
                text = when (state.appearance) {
                    ServiceSelectorAppearance.ICONS_ONLY -> if (serviceIcon == null) service.name else null
                    else -> service.name
                }
                toolTipText = service.name; isSelected = service.id == selected?.id
                isEnabled = !state.isLoading; isOpaque = false
                putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
                margin = compactButtonMargin()
                addActionListener { onServiceSelected(ServiceRole.TRANSLATOR, service.id) }
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) onConfigureService(service.id) }
                })
            }.also(group::add)
        }
        activeSlot.removeAll()
        activeButton = selected?.let { buttons[it.id] }
        activeButton?.let { activeSlot.add(it) }
        remainingButtons = services.filter { it.id != selected?.id }.mapNotNull { buttons[it.id] }
        configureActive.isEnabled = !state.isLoading && selected != null
        overflowMenu.isEnabled = !state.isLoading
        fitClassicButtons()
    }

    /** Keep the active provider and only whole buttons in the row. Every provider remains in More. */
    private fun fitClassicButtons() {
        val width = classic.width
        if (width <= 0) return
        val gap = UIScale.scale(2)
        val activeWidth = activeButton?.preferredSize?.width ?: 0
        overflowMenu.isVisible = false
        val needed = remainingButtons.sumOf { it.preferredSize.width + gap }
        val basicSpace = width - classic.insets.left - classic.insets.right - activeWidth -
            classicControls.preferredSize.width - gap * 4
        val overflow = needed > basicSpace
        overflowMenu.isVisible = overflow
        val room = (width - classic.insets.left - classic.insets.right - activeWidth -
            classicControls.preferredSize.width - gap * 4).coerceAtLeast(0)
        otherButtons.removeAll()
        var used = 0
        for (button in remainingButtons) {
            val next = button.preferredSize.width + gap
            if (used + next > room) break
            otherButtons.add(button)
            used += next
        }
        overflowMenu.isVisible = overflow || otherButtons.componentCount < remainingButtons.size
        classic.revalidate()
        classic.repaint()
    }

    private fun rebuildEnhanced() {
        enhanced.removeAll()
        listOf(ServiceRole.TRANSLATOR to "Translate", ServiceRole.DICTIONARY to "Dictionary", ServiceRole.TTS to "Voice")
            .forEach { (type, label) ->
                val services = state.availableServices.filter { it.type == type }
                if (services.isNotEmpty()) enhanced.add(createServicePicker(type, label, services))
            }
    }

    private fun createServicePicker(type: ServiceRole, label: String, services: List<ServiceInfo>): JComponent {
        val combo = JComboBox(services.toTypedArray()).apply {
            renderer = ServiceRenderer(); selectedItem = services.find { it.id == state.selectedServices[type] } ?: services.first()
            isEnabled = !state.isLoading; toolTipText = "Select $label service"
            minimumSize = Dimension(0, preferredSize.height)
            addActionListener { (selectedItem as? ServiceInfo)?.let { onServiceSelected(type, it.id) } }
        }
        val gear = JButton(iconManager.getIcon(Icons.SETTINGS, 16, 16)).apply {
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

    /** Every service, including the active one, is reachable with one menu selection. */
    private fun showOverflowMenu(anchor: JComponent) {
        if (state.availableTranslators.isEmpty()) return
        val menu = buildOverflowMenu()
        menu.show(anchor, 0, anchor.height)
    }

    internal fun overflowMenuForTest(): JPopupMenu = buildOverflowMenu()

    private fun buildOverflowMenu(): JPopupMenu {
        val menu = JPopupMenu()
        val group = ButtonGroup()
        state.availableTranslators.forEach { service ->
            menu.add(JRadioButtonMenuItem(service.name, loadIcon(service), service.id == state.selectedTranslatorId).apply {
                isEnabled = !state.isLoading
                addActionListener { onServiceSelected(ServiceRole.TRANSLATOR, service.id) }
                group.add(this)
            })
        }
        return menu
    }

    private fun loadIcon(service: ServiceInfo): Icon? = service.iconPath?.let { iconManager.getIcon(service.id, it, ICON_SIZE, ICON_SIZE) }

    private fun compactButtonMargin() = Insets(
        UIScale.scale(1), UIScale.scale(6), UIScale.scale(1), UIScale.scale(6)
    )

    private inner class ServiceRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component =
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                val service = value as? ServiceInfo; text = service?.name.orEmpty(); icon = service?.let(::loadIcon)
            }
    }
}

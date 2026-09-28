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
    private var activeServiceId: String? = null
    // Classic keeps the original QTranslate's adjacent bottom tabs, with complete labels and
    // a single adaptive overflow menu for today's larger plugin set.
    private val serviceStrip = object : JPanel(StretchTabsLayout()) {
        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            val segments = components.sortedBy { it.x }
            val oldColor = g.color
            g.color = UIManager.getColor("Component.borderColor") ?: Color.GRAY
            val inset = UIScale.scale(4)
            segments.drop(1).forEach { segment ->
                g.drawLine(segment.x, inset, segment.x, height - inset - 1)
            }
            g.color = oldColor
        }
    }.apply { isOpaque = false }
    private var activeButton: JToggleButton? = null
    private var remainingButtons: List<JToggleButton> = emptyList()
    private val overflowMenu = JButton("More ▾").apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        putClientProperty(FlatClientProperties.STYLE, "arc: 0")
        margin = compactButtonMargin()
        toolTipText = "All services"
        addActionListener { showOverflowMenu(this) }
    }
    private val configureActive = JButton(iconManager.getIcon(Icons.SETTINGS, 16, 16)).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        putClientProperty(FlatClientProperties.STYLE, "arc: 0")
        margin = compactButtonMargin()
        toolTipText = "Configure active translation service"
        addActionListener { activeServiceId?.let(onConfigureService) }
    }
    private val classic = object : JPanel(BorderLayout(UIScale.scale(6), 0)) {
        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            val oldColor = g.color
            g.color = UIManager.getColor("Component.borderColor") ?: Color.GRAY
            g.drawLine(0, height - 1, width - 1, height - 1)
            g.color = oldColor
        }
    }.apply {
        isOpaque = false
        add(serviceStrip, BorderLayout.CENTER)
        add(configureActive, BorderLayout.LINE_END)
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
        serviceStrip.componentOrientation = componentOrientation
        if (state.style == ServiceSelectorStyle.CLASSIC) rebuildClassic() else rebuildEnhanced()
        (layout as CardLayout).show(this, if (state.style == ServiceSelectorStyle.CLASSIC) CLASSIC else ENHANCED)
        revalidate(); repaint()
    }

    private fun rebuildClassic() {
        val services = state.availableTranslators
        val selected = services.find { it.id == state.selectedTranslatorId } ?: services.firstOrNull()
        activeServiceId = selected?.id
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
                putClientProperty(FlatClientProperties.STYLE, "arc: 0")
                margin = compactButtonMargin()
                addActionListener { onServiceSelected(ServiceRole.TRANSLATOR, service.id) }
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) onConfigureService(service.id) }
                })
            }.also(group::add)
        }
        activeButton = selected?.let { buttons[it.id] }
        remainingButtons = services.filter { it.id != selected?.id }.mapNotNull { buttons[it.id] }
        configureActive.isEnabled = !state.isLoading && selected != null
        overflowMenu.isEnabled = !state.isLoading
        fitClassicButtons()
    }

    /** Keep the active provider and only whole buttons in the row. Every provider remains in More. */
    private fun fitClassicButtons() {
        val width = classic.width
        if (width <= 0) return
        val activeWidth = activeButton?.preferredSize?.width ?: 0
        val room = (width - classic.insets.left - classic.insets.right -
            configureActive.preferredSize.width - UIScale.scale(6) - activeWidth).coerceAtLeast(0)
        val overflow = remainingButtons.sumOf { it.preferredSize.width } > room
        val serviceRoom = (room - if (overflow) overflowMenu.preferredSize.width else 0).coerceAtLeast(0)
        val visibleButtons = mutableListOf<AbstractButton>()
        activeButton?.let { visibleButtons.add(it) }
        var used = 0
        for (button in remainingButtons) {
            if (used + button.preferredSize.width > serviceRoom) break
            visibleButtons.add(button)
            used += button.preferredSize.width
        }
        if (overflow) visibleButtons.add(overflowMenu)
        if (serviceStrip.components.toList() != visibleButtons) {
            serviceStrip.removeAll()
            visibleButtons.forEach { serviceStrip.add(it) }
        }
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
            menu.add(JRadioButtonMenuItem(service.name, loadIcon(service), service.id == activeServiceId).apply {
                isEnabled = !state.isLoading
                addActionListener { onServiceSelected(ServiceRole.TRANSLATOR, service.id) }
                group.add(this)
            })
        }
        return menu
    }

    private fun loadIcon(service: ServiceInfo): Icon? = service.iconPath?.let { iconManager.getIcon(service.id, it, ICON_SIZE, ICON_SIZE) }

    private fun compactButtonMargin() = Insets(
        UIScale.scale(2), UIScale.scale(4), UIScale.scale(2), UIScale.scale(4)
    )

    /** Share spare width between whole tabs; never shrink a label to fill an arbitrary cell. */
    private class StretchTabsLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension {
            val children = parent.components.filter { it.isVisible }
            val insets = parent.insets
            return Dimension(
                children.sumOf { it.preferredSize.width } + insets.left + insets.right,
                (children.maxOfOrNull { it.preferredSize.height } ?: 0) + insets.top + insets.bottom
            )
        }

        override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

        override fun layoutContainer(parent: Container) {
            val children = parent.components.filter { it.isVisible }
            if (children.isEmpty()) return
            val insets = parent.insets
            val widths = children.map { it.preferredSize.width }.toMutableList()
            val spare = (parent.width - insets.left - insets.right - widths.sum()).coerceAtLeast(0)
            widths.indices.forEach { index ->
                widths[index] += spare / widths.size + if (index < spare % widths.size) 1 else 0
            }
            val height = (parent.height - insets.top - insets.bottom).coerceAtLeast(0)
            var edge = if (parent.componentOrientation.isLeftToRight) insets.left else parent.width - insets.right
            children.forEachIndexed { index, child ->
                if (parent.componentOrientation.isLeftToRight) {
                    child.setBounds(edge, insets.top, widths[index], height)
                    edge += widths[index]
                } else {
                    edge -= widths[index]
                    child.setBounds(edge, insets.top, widths[index], height)
                }
            }
        }
    }

    private inner class ServiceRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component =
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                val service = value as? ServiceInfo; text = service?.name.orEmpty(); icon = service?.let(::loadIcon)
            }
    }
}

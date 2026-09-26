package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import javax.swing.*
import kotlin.math.max

class TranslatorPopupButton(
    private val iconManager: IconManager,
    private val onTranslatorSelected: (serviceId: String) -> Unit,
    /** When true the button shows "[icon] Name" as one selector control instead of icon only. */
    textMode: Boolean = false
) : JPanel(BorderLayout()), Renderable<TranslatorSelectorState> {

    var textMode: Boolean = textMode
        set(value) {
            field = value
            chevronLabel.isVisible = value
        }

    private companion object {
        const val ICON_SIZE = 16
    }

    private var currentState: TranslatorSelectorState? = null
    private val arrowIcon: Icon = UIManager.getIcon("Table.descendingSortIcon")

    private val actionButton = JButton().apply {
        isFocusable = false
        cursor = Cursor(Cursor.HAND_CURSOR)
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")

        addActionListener { showPopupMenu() }
    }

    /**
     * Trailing dropdown affordance for text mode, placed at the logical
     * line end so LTR shows icon -> name -> chevron and RTL mirrors it.
     * Icon-only mode keeps the historical composite icon and hides this.
     */
    private val chevronLabel = JLabel().apply {
        isOpaque = false
        icon = arrowIcon
        isFocusable = false
        cursor = Cursor(Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (actionButton.isEnabled) showPopupMenu()
            }
        })
    }

    init {
        isOpaque = false
        add(actionButton, BorderLayout.CENTER)
        add(chevronLabel, BorderLayout.LINE_END)
        updateChevronVisibility()
    }

    override fun render(state: TranslatorSelectorState) {
        this.currentState = state

        val selectedService = state.selectedTranslatorId?.let { id ->
            state.availableTranslators.find { it.id == id }
        }

        val serviceIcon = selectedService?.iconPath?.let { path ->
            iconManager.getIcon(selectedService.id, path, ICON_SIZE, ICON_SIZE)
        } ?: createPlaceholderIcon()

        if (textMode) {
            // Text-mode identity order: icon -> provider name -> chevron.
            // The button owns icon + name; the chevron trails at LINE_END.
            actionButton.icon = serviceIcon
            actionButton.text = selectedService?.name ?: "Select Translator"
            actionButton.horizontalAlignment = SwingConstants.LEADING
        } else {
            actionButton.icon = CompositeIcon(serviceIcon, arrowIcon)
            actionButton.text = null
        }
        updateChevronVisibility()

        actionButton.toolTipText = selectedService?.name ?: "Select Translator"
        actionButton.isEnabled = !state.isLoading && state.availableTranslators.isNotEmpty()
        chevronLabel.isEnabled = actionButton.isEnabled
    }

    private fun updateChevronVisibility() {
        chevronLabel.isVisible = textMode
    }

    /** Exposed for tests: leading action owning icon + name. */
    fun buttonForTest(): JButton = actionButton

    /** Exposed for tests: trailing chevron affordance (text mode only). */
    fun chevronForTest(): JLabel = chevronLabel

    private fun showPopupMenu() {
        val state = this.currentState ?: return
        if (state.availableTranslators.isEmpty()) return

        val popupMenu = JPopupMenu()
        state.availableTranslators.forEach { service ->
            val icon = service.iconPath?.let { path ->
                iconManager.getIcon(service.id, path, ICON_SIZE, ICON_SIZE)
            }
            val menuItem = JMenuItem(service.name, icon)
            menuItem.addActionListener { onTranslatorSelected(service.id) }
            popupMenu.add(menuItem)
        }
        popupMenu.show(actionButton, 0, actionButton.height)
    }

    private fun createPlaceholderIcon(): Icon {
        return object : Icon {
            override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {}
            override fun getIconWidth(): Int = ICON_SIZE
            override fun getIconHeight(): Int = ICON_SIZE
        }
    }

    private class CompositeIcon(
        private val left: Icon,
        private val right: Icon,
        private val gap: Int = 8
    ) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val leftY = y + (iconHeight - left.iconHeight) / 2
            left.paintIcon(c, g, x, leftY)

            val rightY = y + (iconHeight - right.iconHeight) / 2
            right.paintIcon(c, g, x + left.iconWidth + gap, rightY)
        }

        override fun getIconWidth(): Int = left.iconWidth + gap + right.iconWidth
        override fun getIconHeight(): Int = max(left.iconHeight, right.iconHeight)
    }
}
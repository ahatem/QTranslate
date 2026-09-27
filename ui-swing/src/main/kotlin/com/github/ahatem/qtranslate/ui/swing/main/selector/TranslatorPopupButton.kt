package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.ComponentOrientation
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Insets
import javax.swing.Icon
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingConstants
import javax.swing.UIManager
import kotlin.math.max

/**
 * The translator picker: one toolbar button that shows the current translator and opens a menu of
 * the others.
 *
 * Text mode reads `[icon] Name v`: the identity itself is the thing to click, and the trailing
 * chevron is drawn by the button, not laid out beside it, so there is one hover state, one focus
 * ring and one click target however wide the row is. Icon-only mode keeps the historical composite
 * of icon and arrow for the compact popups.
 *
 * Reachable from the keyboard like any button (Space or Enter opens the menu); the mouse does not
 * take focus from the text being worked on.
 */
class TranslatorPopupButton(
    private val iconManager: IconManager,
    private val onTranslatorSelected: (serviceId: String) -> Unit,
    /** When true the button shows "[icon] Name v" as one selector control instead of icon only. */
    textMode: Boolean = false
) : FlatButton(), Renderable<TranslatorSelectorState> {

    var textMode: Boolean = textMode
        set(value) {
            field = value
            refreshFromState()
        }

    /** Names what the control does; when null the tooltip is the current translator's name. */
    var actionTooltip: String? = null
        set(value) {
            field = value
            refreshFromState()
        }

    private companion object {
        const val ICON_SIZE = 16
        const val CHEVRON_GAP = 6
    }

    private var currentState: TranslatorSelectorState? = null
    private val arrowIcon: Icon get() = UIManager.getIcon("Table.descendingSortIcon")

    init {
        buttonType = ButtonType.toolBarButton
        isFocusable = true
        isRequestFocusEnabled = false
        cursor = Cursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.LEADING
        addActionListener { showPopupMenu() }
    }

    override fun render(state: TranslatorSelectorState) {
        currentState = state
        refreshFromState()
    }

    private fun refreshFromState() {
        val state = currentState ?: return

        val selectedService = state.selectedTranslatorId?.let { id ->
            state.availableTranslators.find { it.id == id }
        }
        val serviceIcon = selectedService?.iconPath?.let { path ->
            iconManager.getIcon(selectedService.id, path, ICON_SIZE, ICON_SIZE)
        } ?: createPlaceholderIcon()

        if (textMode) {
            icon = serviceIcon
            text = selectedService?.name ?: "Select Translator"
        } else {
            icon = CompositeIcon(serviceIcon, arrowIcon)
            text = null
        }
        applyChevronRoom()

        toolTipText = actionTooltip ?: selectedService?.name ?: "Select Translator"
        isEnabled = !state.isLoading && state.availableTranslators.isNotEmpty()
        revalidate()
        repaint()
    }

    /** The room the chevron needs at the trailing edge, on whichever side that is. */
    private fun applyChevronRoom() {
        val base = UIManager.getInsets("Button.toolbar.margin") ?: Insets(3, 3, 3, 3)
        val room = if (textMode) arrowIcon.iconWidth + UIScale.scale(CHEVRON_GAP) else 0
        val leftToRight = componentOrientation.isLeftToRight
        margin = Insets(
            base.top, base.left + if (leftToRight) 0 else room,
            base.bottom, base.right + if (leftToRight) room else 0
        )
    }

    override fun setComponentOrientation(orientation: ComponentOrientation) {
        super.setComponentOrientation(orientation)
        applyChevronRoom()
    }

    override fun updateUI() {
        super.updateUI()
        if (currentState != null) applyChevronRoom()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!textMode) return
        val chevron = arrowIcon
        val y = (height - chevron.iconHeight) / 2
        val inset = UIScale.scale(CHEVRON_GAP) / 2 + (UIManager.getInsets("Button.toolbar.margin")?.right ?: 3)
        val x = if (componentOrientation.isLeftToRight) width - inset - chevron.iconWidth else inset
        chevron.paintIcon(this, g, x, y)
    }

    private fun showPopupMenu() {
        val state = currentState ?: return
        if (state.availableTranslators.isEmpty()) return

        val popupMenu = JPopupMenu()
        state.availableTranslators.forEach { service ->
            val serviceIcon = service.iconPath?.let { path ->
                iconManager.getIcon(service.id, path, ICON_SIZE, ICON_SIZE)
            }
            popupMenu.add(JMenuItem(service.name, serviceIcon).apply {
                addActionListener { onTranslatorSelected(service.id) }
            })
        }
        popupMenu.show(this, 0, height)
    }

    private fun createPlaceholderIcon(): Icon = object : Icon {
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {}
        override fun getIconWidth(): Int = ICON_SIZE
        override fun getIconHeight(): Int = ICON_SIZE
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

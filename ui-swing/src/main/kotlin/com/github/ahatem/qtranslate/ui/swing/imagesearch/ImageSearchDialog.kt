package com.github.ahatem.qtranslate.ui.swing.imagesearch

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.PopupSizing
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.util.toDimension
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.FloatingPopupBehavior
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * Floating popup showing pictures for a term.
 *
 * The window around [ImageSearchPanel]: undecorated, always on top, movable, resizable, dismissed
 * with Escape, the visual sibling of the quick dictionary popup because it answers the same
 * question from the same gesture on the same selection. What it shows is the panel's; what it adds
 * is a title, a pin, and everything to do with being a window.
 *
 * It deliberately does *not* copy the dictionary popup's idle-hide timer. A definition is read in a
 * couple of seconds; a grid of pictures is looked over, compared, and clicked through, and a popup
 * that vanishes mid-comparison would be worse than one the reader closes themselves.
 */
class ImageSearchDialog(
    /**
     * The main window, used only to position against. It is deliberately NOT this dialog's
     * owner -- see FloatingPopupBehavior for why a tray application must not own these.
     */
    private val owner: Frame,
    private val iconManager: IconManager
) : JDialog(null as Frame?, ModalityType.MODELESS), Renderable<ImageSearchDialogState> {

    private companion object {
        const val PINNED_BORDER_WIDTH = 4
    }

    private val panel = ImageSearchPanel()

    private val titleLabel = JLabel("").apply { putClientProperty("FlatLaf.styleClass", "h4") }
    private val pinButton = createButtonWithIcon(iconManager, Icons.PIN, 14)
    private val closeButton = createButtonWithIcon(iconManager, Icons.CLOSE, 16)

    /** Kept as a field because it is both the drag handle and the title row. */
    private var header: JComponent

    private var currentState: ImageSearchDialogState? = null
    private var isPinned = false

    /** The trigger this popup last reacted to; see [ImageSearchDialogState.triggerCount]. */
    private var lastTriggerCount = 0

    /** Undecorated, always on top, draggable, resizable, Escape-dismissed — shared with the
     *  translate and dictionary popups so all three behave the same as windows. */
    private val popup = FloatingPopupBehavior(
        window = this,
        owner = owner,
        minimumSize = Dimension(PopupSizing.minWidth(), UIScale.scale(300)),
        pinnedBorderWidth = PINNED_BORDER_WIDTH
    )

    init {
        focusableWindowState = true

        pinButton.addActionListener { currentState?.onPinToggled?.invoke() }
        closeButton.addActionListener { currentState?.onClose?.invoke() }

        header = buildHeader()
        contentPane = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(panel, BorderLayout.CENTER)
        }

        popup.installDrag(header) { position -> currentState?.onSavePosition?.invoke(position) }
        popup.installResize({ size -> currentState?.onSaveSize?.invoke(size) })
        // Escape unwinds one step at a time: out of the enlarged image first, and only then out
        // of the popup. Closing outright would throw away the search as well.
        popup.installEscape {
            if (!panel.stepBack()) currentState?.onClose?.invoke()
            true
        }
        popup.installTheme(::refreshTheme)
        popup.applyPinBorder(false)
    }

    /** Re-applies colours a theme switch does not revisit. */
    private fun refreshTheme() {
        applyPinStyle(isPinned)
        panel.refreshTheme()
        revalidate()
        repaint()
    }

    private fun buildHeader(): JComponent {
        val buttons = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(pinButton)
            add(Box.createHorizontalStrut(2))
            add(closeButton)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(8, 10, 0, 8)
            add(titleLabel, BorderLayout.CENTER)
            add(buttons, BorderLayout.LINE_END)
        }
    }

    override fun render(state: ImageSearchDialogState) {
        val visibilityChanged = isVisible != state.isVisible

        if (visibilityChanged) {
            if (state.isVisible) {
                currentState = state
                popup.resetManualMove()
                applyText(state)
                panel.render(state.toPanelState())
                size = state.config.lastKnownSize.toDimension()
                applyPosition(state.config)
                // Before it is shown: changing opacity on a window already on screen repaints it
                // at the new value, which reads as a flicker on open.
                applyTransparency(state.config)
                isVisible = true
                // Queued rather than requested inline: focus cannot be taken until the window is
                // actually on screen, so asking during the same event does nothing and the field
                // silently fails to accept typing.
                SwingUtilities.invokeLater { panel.focusSearchField() }
            } else {
                saveGeometry()
                isVisible = false
            }
            return
        }

        if (!isVisible) return

        val pinChanged = isPinned != state.isPinned
        val retriggered = lastTriggerCount != state.triggerCount
        lastTriggerCount = state.triggerCount

        currentState = state
        applyText(state)
        panel.render(state.toPanelState())
        if (pinChanged) applyPinStyle(state.isPinned)
        // Asked for again while open: bring it back to the front of the user's attention rather
        // than closing and reopening it.
        if (retriggered) toFront()
    }

    private fun ImageSearchDialogState.toPanelState() = ImageSearchPanelState(
        isLoading = isLoading,
        results = results,
        searchedTerm = searchedTerm,
        hasFailed = hasFailed,
        strings = strings,
        onSearch = onSearch,
        onImageOpened = onImageOpened
    )

    private fun applyText(state: ImageSearchDialogState) {
        isPinned = state.isPinned
        titleLabel.text = state.strings.title
        pinButton.toolTipText = if (state.isPinned) state.strings.unpinTooltip else state.strings.pinTooltip
        closeButton.toolTipText = state.strings.closeTooltip
    }

    private fun applyPinStyle(pinned: Boolean) = popup.applyPinBorder(pinned)

    private fun applyPosition(config: ImageSearchConfig) {
        if (config.positionNearMouse) popup.positionNearMouse() else popup.positionBesideOwner()
    }

    /**
     * Applies the configured transparency.
     *
     * Guarded because a window that is not translucency-capable throws rather than declining, and
     * an opacity of exactly 1 is rejected on some platforms after the window is displayable.
     */
    private fun applyTransparency(config: ImageSearchConfig) {
        val target = ((100 - config.transparencyPercentage).coerceIn(1, 100)) / 100f
        runCatching { opacity = target }
    }

    private fun saveGeometry() {
        currentState?.onSavePosition?.invoke(Position(location.x, location.y))
        currentState?.onSaveSize?.invoke(Size(size.width, size.height))
    }

    /**
     * Releases the thumbnail pool along with the window.
     *
     * Overrides the real `dispose` rather than adding a variant beside it: the previous
     * `dispose(Boolean)` was never called from anywhere, so the pool it was meant to shut down
     * never was.
     */
    override fun dispose() {
        panel.dispose()
        popup.uninstallTheme()
        super.dispose()
    }
}

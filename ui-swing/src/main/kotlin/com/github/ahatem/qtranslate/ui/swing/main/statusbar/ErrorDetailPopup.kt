package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.MatteBorder

/**
 * A lightweight, non-blocking popup anchored to the status-bar chip that
 * displays the full error/warning message and lets the user copy it.
 *
 * Uses [JPopupMenu] so it auto-dismisses when the user clicks elsewhere —
 * no modal dialogs, no stolen focus.
 *
 * Layout:
 * ```
 * ┌──────────────────────────────────────────┐
 * │  Error                              [×]  │  ← header: bold colored type + close
 * ├──────────────────────────────────────────┤
 * │                                          │
 * │  Full message text, word-wrapped…        │  ← scrollable body (12 px text padding)
 * │                                          │
 * ├──────────────────────────────────────────┤
 * │                            [   Copy   ]  │  ← footer: primary action button
 * └──────────────────────────────────────────┘
 * ```
 */
class ErrorDetailPopup(private val iconManager: IconManager) {

    companion object {
        private const val H_PAD = 14   // horizontal padding used throughout
        private const val V_PAD = 8    // vertical padding for header / footer
        private const val TEXT_PAD = 12 // inner text-area padding (top/bottom)
        private const val POPUP_WIDTH = 420
        private const val BODY_HEIGHT = 110
        private const val COPY_FEEDBACK_MS = 1200
    }

    // -----------------------------------------------------------------------
    // Localizable labels — update before calling show()
    // -----------------------------------------------------------------------

    var errorLabel: String   = "Error"
    var warningLabel: String = "Warning"
    var copyLabel: String    = "Copy"
    var copiedLabel: String  = "Copied!"

    // -----------------------------------------------------------------------
    // UI components
    // -----------------------------------------------------------------------

    private val typeLabel = JLabel().apply {
        font = UIManager.getFont("Label.font")?.deriveFont(Font.BOLD) ?: font?.deriveFont(Font.BOLD)
    }

    private val closeButton = createButtonWithIcon(iconManager, "icons/lucide/close.svg", 14).apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                background          = UIManager.getColor("InternalFrame.closeHoverBackground")
                foreground          = UIManager.getColor("InternalFrame.closeHoverForeground")
                isContentAreaFilled = true
                isBorderPainted     = false
            }
            override fun mouseExited(e: MouseEvent) {
                isContentAreaFilled = false
                foreground          = null
            }
            override fun mousePressed(e: MouseEvent) {
                background = UIManager.getColor("InternalFrame.closePressedBackground")
                foreground = UIManager.getColor("InternalFrame.closePressedForeground")
            }
            override fun mouseReleased(e: MouseEvent) {
                if (contains(e.point)) {
                    background = UIManager.getColor("InternalFrame.closeHoverBackground")
                    foreground = UIManager.getColor("InternalFrame.closeHoverForeground")
                }
            }
        })
    }

    /**
     * Primary action button — a standard [JButton] (not a toolbar icon button) so
     * FlatLaf renders it with a visible border, making it read as the main action.
     */
    private val copyButton = JButton().apply { isFocusable = false }

    /**
     * Text area for the full error message.
     *
     * Padding note: we use [JTextArea.border] (an [EmptyBorder]) instead of
     * [JTextComponent.margin] because FlatLaf's UI delegate may reset the margin
     * when it installs its own compound border. An [EmptyBorder] set here survives
     * the UI installation since it becomes part of the component's border chain.
     */
    private val textArea = JTextArea().apply {
        isEditable    = false
        lineWrap      = true
        wrapStyleWord = true
        rows          = 5
        columns       = 38
        // EmptyBorder provides reliable text-level padding regardless of LAF.
        border = BorderFactory.createEmptyBorder(TEXT_PAD, H_PAD, TEXT_PAD, H_PAD)
    }

    private val popup = JPopupMenu()
    private var copyFeedbackTimer: Timer? = null

    val isVisible: Boolean get() = popup.isVisible

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    init {
        closeButton.addActionListener { popup.isVisible = false }
        copyButton.addActionListener {
            textArea.text.copyToClipboard()
            showCopyFeedback()
        }

        val content = JPanel(BorderLayout()).apply {
            add(buildHeader(),  BorderLayout.NORTH)
            add(buildBody(),    BorderLayout.CENTER)
            add(buildFooter(),  BorderLayout.SOUTH)
        }

        popup.add(content)
        popup.isFocusable = true
    }

    // -----------------------------------------------------------------------
    // Section builders
    // -----------------------------------------------------------------------

    private fun buildHeader(): JPanel {
        val borderColor = UIManager.getColor("Component.borderColor") ?: Color.GRAY
        return JPanel(BorderLayout(H_PAD, 0)).apply {
            isOpaque = false
            border   = BorderFactory.createCompoundBorder(
                // Bottom separator between header and body.
                MatteBorder(0, 0, 1, 0, borderColor),
                // Inner padding — symmetric H_PAD on both sides so it mirrors
                // the text-area padding and looks even for both LTR and RTL.
                BorderFactory.createEmptyBorder(V_PAD, H_PAD, V_PAD, H_PAD)
            )
            // LINE_END / LINE_START are RTL-aware unlike EAST / WEST.
            add(typeLabel,   BorderLayout.CENTER)
            add(closeButton, BorderLayout.LINE_END)
        }
    }

    private fun buildBody(): JScrollPane {
        return JScrollPane(textArea).apply {
            preferredSize             = Dimension(POPUP_WIDTH, BODY_HEIGHT)
            // Remove all scroll-pane chrome — padding lives on the text area itself.
            border                    = null
            viewport.border           = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    private fun buildFooter(): JPanel {
        val borderColor = UIManager.getColor("Component.borderColor") ?: Color.GRAY
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = BorderFactory.createCompoundBorder(
                MatteBorder(1, 0, 0, 0, borderColor),
                BorderFactory.createEmptyBorder(V_PAD, H_PAD, V_PAD, H_PAD)
            )
            // LINE_END so the button sits on the trailing edge (right in LTR, left in RTL).
            add(copyButton, BorderLayout.LINE_END)
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Shows the popup anchored just above [anchorComponent].
     * Colors and font are resolved from [UIManager] at call time so the popup
     * always matches the current theme.
     *
     * [anchorComponent]'s [ComponentOrientation] is propagated to all children
     * so text alignment and button placement flip correctly for RTL languages.
     */
    fun show(message: String, type: NotificationType, anchorComponent: Component) {
        // Sync labels (may have been updated after construction).
        typeLabel.text       = if (type == NotificationType.ERROR) errorLabel else warningLabel
        typeLabel.foreground = typeColor(type)
        if (copyButton.text != copiedLabel) copyButton.text = copyLabel

        // Resolve live theme colors for the body so the popup matches the active LAF.
        textArea.background = UIManager.getColor("Panel.background") ?: Color.WHITE
        textArea.foreground = UIManager.getColor("Label.foreground") ?: Color.BLACK
        textArea.font       = UIManager.getFont("Label.font")        ?: textArea.font

        textArea.text          = message
        textArea.caretPosition = 0

        // Propagate the invoker's orientation so RTL locales get mirrored layout.
        val orientation = anchorComponent.componentOrientation
        popup.applyComponentOrientation(orientation)
        textArea.componentOrientation = orientation

        // Anchor above the chip: x=0 (leading edge), y = negative popup height.
        popup.show(anchorComponent, 0, -popup.preferredSize.height)
    }

    /** Hides the popup. Safe to call when already hidden. */
    fun dismiss() {
        popup.isVisible = false
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun showCopyFeedback() {
        copyFeedbackTimer?.stop()

        copyButton.text       = copiedLabel
        copyButton.foreground = UIManager.getColor("Button.successForeground") ?: Color(34, 197, 94)

        copyFeedbackTimer = Timer(COPY_FEEDBACK_MS) {
            copyButton.text      = copyLabel
            copyButton.foreground = null
            (it.source as Timer).stop()
        }.apply { isRepeats = false; start() }
    }

    private fun typeColor(type: NotificationType): Color = when (type) {
        NotificationType.ERROR   -> UIManager.getColor("Actions.Red")    ?: Color(0xE05555)
        NotificationType.WARNING -> UIManager.getColor("Actions.Yellow") ?: Color(0xE2A53A)
        else                     -> UIManager.getColor("Label.foreground") ?: Color.GRAY
    }
}

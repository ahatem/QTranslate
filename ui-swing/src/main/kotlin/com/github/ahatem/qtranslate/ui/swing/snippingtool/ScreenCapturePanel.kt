package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.formdev.flatlaf.FlatClientProperties
import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.math.roundToInt

class ScreenCapturePanel(
    private val onTranslate: (BufferedImage) -> Unit,
    private val onCopyText: (BufferedImage) -> Unit,
    private val onCopyImage: (BufferedImage) -> Unit,
    private val onSaveImage: (BufferedImage) -> Unit,
    private val onRecrop: () -> Unit,
    private val onCancel: () -> Unit,
    private val buttonLabels: ButtonLabels = ButtonLabels(),
    private val buttonTooltips: ButtonTooltips = ButtonTooltips(),
) : JLayeredPane() {

    data class ButtonLabels(
        val translate: String  = "Translate",
        val copyText: String   = "Copy Text",
        val copyImage: String  = "Copy Image",
        val saveImage: String  = "Save Image",
        val recrop: String     = "Re-crop",
        val cancel: String     = "Cancel",
        val moreActions: String = "More actions",
    )

    data class ButtonTooltips(
        val translate: String  = "OCR and translate the captured region",
        val copyText: String   = "Recognize text and copy to clipboard",
        val copyImage: String  = "Copy image to clipboard",
        val saveImage: String  = "Save captured image as PNG",
        val recrop: String     = "Clear selection and draw a new one",
        val cancel: String     = "Close the capture tool",
    )

    private val actionButtons = mutableMapOf<CaptureToolbarAction, JButton>()
    private val selectionDependentButtons = mutableListOf<JButton>()

    private var currentState: ScreenCaptureState? = null

    /**
     * The primary Translate action of the capture toolbar. Exposed so the owning
     * dialog can install it as the root pane's default button (#364). It is
     * disabled while no valid selection exists, so Enter can only trigger a real
     * capture.
     */
    val translateButton: JButton = createTranslateButton()

    private val buttonsPanel: JPanel
    private val fullToolbarContents: List<Component>
    private val fullToolbarSize: Dimension
    private val fullToolbarBorder: javax.swing.border.Border
    private val fullToolbarLayout: LayoutManager
    private var compactToolbar = false
    private val overflowButton by lazy {
        JButton("\u22ef").apply {
            toolTipText = buttonLabels.moreActions
            accessibleContext.accessibleName = buttonLabels.moreActions
            addActionListener { overflowMenu().show(this, 0, height) }
        }
    }

    val currentStatePublic: ScreenCaptureState?
        get() = currentState

    init {
        isOpaque = false
        layout = null

        buttonsPanel = createButtonsPanel()
        fullToolbarContents = buttonsPanel.components.toList()
        fullToolbarSize = Dimension(buttonsPanel.preferredSize)
        fullToolbarBorder = buttonsPanel.border
        fullToolbarLayout = buttonsPanel.layout
        add(buttonsPanel, PALETTE_LAYER)
    }

    fun attachController(controller: ScreenCaptureController) {
        addMouseListener(controller)
        addMouseMotionListener(controller)
        isFocusable = true
        requestFocusInWindow()
    }

    fun render(state: ScreenCaptureState) {
        this.currentState = state
        cursor = Cursor.getPredefinedCursor(state.mouseAction.cursor)

        // The toolbar (and the Translate action with it) only makes sense once a
        // real region is selected; keep the primary action disabled before that so
        // the dialog's default button cannot fire an empty capture (#364). The
        // validity rule mirrors ScreenCaptureController.mouseReleased.
        val selection = state.selection
        val validSelection = selection != null && selection.width > 0 && selection.height > 0
        translateButton.isEnabled = validSelection
        selectionDependentButtons.forEach { it.isEnabled = validSelection }

        if (state.showButtons && state.buttonsPosition != null) {
            buttonsPanel.location = state.buttonsPosition
        } else {
            buttonsPanel.setLocation(-1000, -1000)
        }

        repaint()
    }

    fun getSelectedImage(): BufferedImage? {
        val state = currentState ?: return null
        val selection = state.selection ?: return null
        val panelSize = if (width > 0 && height > 0) {
            size
        } else {
            // Headless tests and a panel that has not been laid out yet still carry a valid
            // selection against the screenshot itself.
            Dimension(state.screenshot.width, state.screenshot.height)
        }
        val imageSelection = CaptureGeometry.selectionToImage(
            selection = selection,
            screenshot = state.screenshot,
            panelSize = panelSize,
        ) ?: return null
        return state.screenshot.getSubimage(
            imageSelection.x,
            imageSelection.y,
            imageSelection.width,
            imageSelection.height,
        )
    }

    /** Invokes a toolbar action as if its button was pressed, if the action is currently valid. */
    fun trigger(action: CaptureToolbarAction) {
        if (action == CaptureToolbarAction.CANCEL) {
            onCancel()
            return
        }
        if (action == CaptureToolbarAction.RECROP) {
            onRecrop()
            return
        }
        if (!translateButton.isEnabled) return
        val image = getSelectedImage() ?: return
        when (action) {
            CaptureToolbarAction.TRANSLATE -> onTranslate(image)
            CaptureToolbarAction.COPY_TEXT -> onCopyText(image)
            CaptureToolbarAction.COPY_IMAGE -> onCopyImage(image)
            CaptureToolbarAction.SAVE_IMAGE -> onSaveImage(image)
            CaptureToolbarAction.RECROP,
            CaptureToolbarAction.CANCEL -> Unit
        }
    }

    /** The button for [action], for tooltip/shortcut tests and future accessibility wiring. */
    internal fun buttonFor(action: CaptureToolbarAction): JButton? = actionButtons[action]

    // ─────────────────────────────────────────────────────────────────────────
    // Painting
    // ─────────────────────────────────────────────────────────────────────────

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        val state = currentState ?: return

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        g2d.drawImage(state.screenshot, 0, 0, width, height, null)

        val overlayAlpha = 200
        val baseColor = UIManager.getColor("Panel.background") ?: Color.BLACK
        g2d.composite = AlphaComposite.SrcOver.derive(overlayAlpha / 255f)
        g2d.color = Color(baseColor.red, baseColor.green, baseColor.blue, overlayAlpha)

        state.selection?.takeIf { it.width > 0 && it.height > 0 }?.let { sel ->
            // Dim everything outside the selection
            g2d.fillRect(0, 0, width, sel.y)
            g2d.fillRect(0, sel.y + sel.height, width, height - (sel.y + sel.height))
            g2d.fillRect(0, sel.y, sel.x, sel.height)
            g2d.fillRect(sel.x + sel.width, sel.y, width - (sel.x + sel.width), sel.height)

            g2d.composite = AlphaComposite.SrcOver

            // Dashed selection border
            val borderColor = UIManager.getColor("Component.focusedBorderColor") ?: Color(0, 120, 215)
            g2d.color = borderColor
            g2d.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(4f, 4f), 0f)
            g2d.drawRect(sel.x, sel.y, sel.width, sel.height)

            // 15% tinted fill inside selection
            val fillColor = UIManager.getColor("TextField.selectionBackground")?.let {
                Color(it.red, it.green, it.blue, (255 * 0.15).roundToInt())
            } ?: Color(0, 120, 215, (255 * 0.15).roundToInt())
            g2d.color = fillColor
            g2d.fillRect(sel.x + 1, sel.y + 1, sel.width - 1, sel.height - 1)

            // Corner resize handles
            paintCornerHandles(g2d, sel)

            // Dimensions badge
            paintDimensionsLabel(g2d, sel)

        } ?: run {
            g2d.fillRect(0, 0, width, height)
        }
    }

    private fun paintCornerHandles(g2d: Graphics2D, sel: Rectangle) {
        val handleSize = 8
        val half = handleSize / 2
        val accentColor = UIManager.getColor("Component.accentColor") ?: Color(0, 120, 215)
        val borderHandleColor = UIManager.getColor("Component.borderColor") ?: Color.BLACK
        val shadowColor = Color(0, 0, 0, 100)

        listOf(
            Point(sel.x, sel.y),
            Point(sel.x + sel.width, sel.y),
            Point(sel.x, sel.y + sel.height),
            Point(sel.x + sel.width, sel.y + sel.height)
        ).forEach { c ->
            g2d.color = shadowColor
            g2d.fillRect(c.x - half + 1, c.y - half + 1, handleSize, handleSize)
            g2d.color = accentColor
            g2d.fillRect(c.x - half, c.y - half, handleSize, handleSize)
            g2d.color = borderHandleColor
            g2d.stroke = BasicStroke(1f)
            g2d.drawRect(c.x - half, c.y - half, handleSize, handleSize)
        }
    }

    private fun paintDimensionsLabel(g2d: Graphics2D, sel: Rectangle) {
        val label = "${sel.width} × ${sel.height} px"
        val labelFont = g2d.font.deriveFont(Font.BOLD, 11f)
        g2d.font = labelFont
        val fm = g2d.fontMetrics
        val textW = fm.stringWidth(label)
        val textH = fm.ascent
        val pad = 4

        val bgW = textW + pad * 2
        val bgH = textH + pad * 2

        // Only draw if there's enough room inside the selection
        if (bgW + 8 > sel.width || bgH + 8 > sel.height) return

        val bgX = (sel.x + sel.width - bgW - 6).coerceAtLeast(sel.x + 2)
        val bgY = (sel.y + sel.height - bgH - 6).coerceAtLeast(sel.y + 2)

        g2d.color = Color(0, 0, 0, 160)
        g2d.fill(RoundRectangle2D.Float(bgX.toFloat(), bgY.toFloat(), bgW.toFloat(), bgH.toFloat(), 6f, 6f))
        g2d.color = Color.WHITE
        g2d.drawString(label, bgX + pad, bgY + pad + textH - fm.descent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Button panel
    // ─────────────────────────────────────────────────────────────────────────

    private fun createTranslateButton(): JButton = JButton(buttonLabels.translate).apply {
        // "default" = filled accent button — primary action
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "default")
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = buttonTooltips.translate
        addActionListener { getSelectedImage()?.let(onTranslate) }
        alignmentY = Component.CENTER_ALIGNMENT
        actionButtons[CaptureToolbarAction.TRANSLATE] = this
    }

    private fun createButtonsPanel(): JPanel {
        val translateBtn = translateButton
        val copyTextBtn  = flatBtn(buttonLabels.copyText,  buttonTooltips.copyText)  { getSelectedImage()?.let(onCopyText)  }
        val copyImageBtn = flatBtn(buttonLabels.copyImage, buttonTooltips.copyImage) { getSelectedImage()?.let(onCopyImage) }
        val saveImageBtn = flatBtn(buttonLabels.saveImage, buttonTooltips.saveImage) { getSelectedImage()?.let(onSaveImage) }
        val recropBtn    = flatBtn(buttonLabels.recrop,    buttonTooltips.recrop)    { onRecrop()    }
        val cancelBtn    = flatBtn(buttonLabels.cancel,    buttonTooltips.cancel)    { onCancel()    }
        actionButtons[CaptureToolbarAction.COPY_TEXT] = copyTextBtn
        actionButtons[CaptureToolbarAction.COPY_IMAGE] = copyImageBtn
        actionButtons[CaptureToolbarAction.SAVE_IMAGE] = saveImageBtn
        actionButtons[CaptureToolbarAction.RECROP] = recropBtn
        actionButtons[CaptureToolbarAction.CANCEL] = cancelBtn
        selectionDependentButtons += listOf(copyTextBtn, copyImageBtn, saveImageBtn)
        selectionDependentButtons += translateBtn

        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val bg = UIManager.getColor("Panel.background") ?: Color(40, 40, 40)
                g2d.color = Color(bg.red, bg.green, bg.blue, 215)
                g2d.fillRoundRect(0, 0, width, height, 14, 14)

                val border = UIManager.getColor("Component.borderColor") ?: Color(80, 80, 80)
                g2d.color = Color(border.red, border.green, border.blue, 160)
                g2d.stroke = BasicStroke(1.5f)
                g2d.draw(RoundRectangle2D.Float(0.75f, 0.75f, width - 1.5f, height - 1.5f, 14f, 14f))

                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(7, 12, 7, 12)

            // ── Group 1: primary action ──────────────────────────────────────
            add(Box.createHorizontalStrut(2))
            add(translateBtn)

            // ── Separator ───────────────────────────────────────────────────
            add(Box.createHorizontalStrut(10))
            add(makeSeparator())
            add(Box.createHorizontalStrut(6))

            // ── Group 2: copy / save ─────────────────────────────────────────
            add(copyTextBtn)
            add(Box.createHorizontalStrut(2))
            add(copyImageBtn)
            add(Box.createHorizontalStrut(2))
            add(saveImageBtn)

            // ── Separator ───────────────────────────────────────────────────
            add(Box.createHorizontalStrut(6))
            add(makeSeparator())
            add(Box.createHorizontalStrut(6))

            // ── Group 3: utility ─────────────────────────────────────────────
            add(recropBtn)
            add(Box.createHorizontalStrut(2))
            add(cancelBtn)
            add(Box.createHorizontalStrut(2))

            size = preferredSize
        }
    }

    /** Flat borderless toolbar-style button. */
    private fun flatBtn(text: String, tooltip: String, action: () -> Unit): JButton =
        JButton(text).apply {
            putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tooltip
            alignmentY = Component.CENTER_ALIGNMENT
            addActionListener { action() }
        }

    /** Thin vertical divider painted inline with the button row. */
    private fun makeSeparator(): Component = object : JComponent() {
        init {
            val h = 22
            preferredSize = Dimension(1, h)
            maximumSize  = Dimension(1, h)
            minimumSize  = Dimension(1, h)
            alignmentY   = Component.CENTER_ALIGNMENT
        }
        override fun paintComponent(g: Graphics) {
            val c = UIManager.getColor("Component.borderColor") ?: Color(100, 100, 100)
            g.color = Color(c.red, c.green, c.blue, 100)
            g.drawLine(0, 2, 0, height - 2)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Position helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun calculateButtonsPosition(
        selection: Rectangle,
        displays: List<Rectangle> = emptyList(),
        virtualOrigin: Point = Point(),
    ): Point {
        if (displays.isEmpty()) {
            return CaptureGeometry.toolbarPosition(selection, buttonsPanel.size, size)
        }
        val fits = displays.any { it.width >= fullToolbarSize.width && it.height >= fullToolbarSize.height }
        val compact = !fits
        if (compactToolbar != compact) {
            compactToolbar = compact
            buttonsPanel.removeAll()
            if (compactToolbar) {
                // Keep the primary button attached for Enter/default-button semantics. All six
                // actions remain available in the overflow menu when a full row cannot fit.
                buttonsPanel.border = BorderFactory.createEmptyBorder()
                buttonsPanel.layout = null
                buttonsPanel.add(translateButton)
                buttonsPanel.add(overflowButton)
            } else {
                buttonsPanel.border = fullToolbarBorder
                buttonsPanel.layout = fullToolbarLayout
                fullToolbarContents.forEach(buttonsPanel::add)
            }
        }
        val preferred = if (compactToolbar) {
            Dimension(translateButton.preferredSize.width + overflowButton.preferredSize.width,
                maxOf(translateButton.preferredSize.height, overflowButton.preferredSize.height))
        } else buttonsPanel.preferredSize
        val desktopSelection = Rectangle(selection).apply { translate(virtualOrigin.x, virtualOrigin.y) }
        val display = CaptureGeometry.toolbarDisplay(desktopSelection, preferred, displays)
        buttonsPanel.size = Dimension(minOf(preferred.width, display.width), minOf(preferred.height, display.height))
        if (compactToolbar) {
            // Reserve overflow first, even when a long primary label or very small usable
            // display cannot fit the pair's minimum sizes. Truncate the primary, never actions.
            val overflowWidth = minOf(overflowButton.preferredSize.width, buttonsPanel.width)
            val primaryWidth = buttonsPanel.width - overflowWidth
            translateButton.setBounds(0, 0, primaryWidth, buttonsPanel.height)
            overflowButton.setBounds(primaryWidth, 0, overflowWidth, buttonsPanel.height)
        } else buttonsPanel.doLayout()
        return CaptureGeometry.toolbarPosition(selection, buttonsPanel.size, size, displays, virtualOrigin)
    }

    /** The same actions and enabled states as the full toolbar, without a second workflow path. */
    internal fun overflowMenu(): JPopupMenu = JPopupMenu().apply {
        CaptureToolbarAction.entries.forEach { action ->
            val button = actionButtons.getValue(action)
            add(JMenuItem(button.text).apply {
                isEnabled = button.isEnabled
                addActionListener { trigger(action) }
            })
        }
    }

    internal fun toolbarBounds(): Rectangle = Rectangle(buttonsPanel.bounds)
    internal fun isToolbarCompact(): Boolean = compactToolbar
    internal fun overflowButtonBounds(): Rectangle = Rectangle(overflowButton.bounds)
}

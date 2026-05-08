package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.ui.swing.shared.util.getVirtualScreenBounds
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
    private val buttonLabels: ButtonLabels = ButtonLabels()
) : JLayeredPane() {

    data class ButtonLabels(
        val translate: String  = "Translate",
        val copyText: String   = "Copy Text",
        val copyImage: String  = "Copy Image",
        val saveImage: String  = "Save Image",
        val recrop: String     = "Re-crop",
        val cancel: String     = "Cancel"
    )

    private var currentState: ScreenCaptureState? = null
    private val buttonsPanel: JPanel

    val currentStatePublic: ScreenCaptureState?
        get() = currentState

    init {
        isOpaque = false
        layout = null

        buttonsPanel = createButtonsPanel()
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
        return state.screenshot.getSubimage(selection.x, selection.y, selection.width, selection.height)
    }

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

    private fun createButtonsPanel(): JPanel {
        val translateBtn = JButton(buttonLabels.translate).apply {
            // "default" = filled accent button — primary action
            putClientProperty(FlatClientProperties.BUTTON_TYPE, "default")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "OCR and translate the captured region"
            addActionListener { getSelectedImage()?.let(onTranslate) }
            alignmentY = Component.CENTER_ALIGNMENT
        }
        val copyTextBtn  = flatBtn(buttonLabels.copyText,  "Recognize text and copy to clipboard")  { getSelectedImage()?.let(onCopyText)  }
        val copyImageBtn = flatBtn(buttonLabels.copyImage, "Copy image to clipboard")                 { getSelectedImage()?.let(onCopyImage) }
        val saveImageBtn = flatBtn(buttonLabels.saveImage, "Save captured image as PNG")              { getSelectedImage()?.let(onSaveImage) }
        val recropBtn    = flatBtn(buttonLabels.recrop,    "Clear selection and draw a new one")      { onRecrop()    }
        val cancelBtn    = flatBtn(buttonLabels.cancel,    "Close the capture tool")                  { onCancel()    }

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

    fun calculateButtonsPosition(selection: Rectangle): Point {
        val panelSize = buttonsPanel.preferredSize
        val screenBounds = getVirtualScreenBounds()
        val padding = 12

        var x = selection.x + (selection.width - panelSize.width) / 2
        var y = selection.y + selection.height + padding

        x = x.coerceAtLeast(screenBounds.x + padding)
            .coerceAtMost(screenBounds.x + screenBounds.width - panelSize.width - padding)

        if (y + panelSize.height > screenBounds.y + screenBounds.height - padding) {
            y = selection.y - panelSize.height - padding
        }

        y = y.coerceAtLeast(screenBounds.y + padding)

        return Point(x, y)
    }
}

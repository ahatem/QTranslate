package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.ui.swing.shared.util.getVirtualScreenBounds
import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.math.roundToInt

class ScreenCapturePanel(
    private val onCapture: (BufferedImage) -> Unit,
    private val onCancel: () -> Unit
) : JLayeredPane() {

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
            // Draw overlay around selection
            g2d.fillRect(0, 0, width, sel.y) // top
            g2d.fillRect(0, sel.y + sel.height, width, height - (sel.y + sel.height)) // bottom
            g2d.fillRect(0, sel.y, sel.x, sel.height) // left
            g2d.fillRect(sel.x + sel.width, sel.y, width - (sel.x + sel.width), sel.height) // right

            g2d.composite = AlphaComposite.SrcOver // reset alpha

            // Selection border
            val borderColor = UIManager.getColor("Component.focusedBorderColor") ?: Color(0, 120, 215)
            val dash = floatArrayOf(4f, 4f)
            g2d.color = borderColor
            g2d.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, dash, 0f)
            g2d.drawRect(sel.x, sel.y, sel.width, sel.height)

            // Semi-transparent fill inside selection
            val fillColor = UIManager.getColor("TextField.selectionBackground")?.let {
                Color(it.red, it.green, it.blue, (255 * 0.15).roundToInt()) // 15%
            } ?: Color(0, 120, 215, (255 * 0.15).roundToInt()) // 15%
            g2d.color = fillColor
            g2d.fillRect(sel.x + 1, sel.y + 1, sel.width - 1, sel.height - 1)

            // Corner handles
            val handleSize = 8
            val half = handleSize / 2
            val accentColor = UIManager.getColor("Component.accentColor") ?: Color(0, 120, 215)
            val borderHandleColor = UIManager.getColor("Component.borderColor") ?: Color.BLACK
            val shadowColor = Color(0, 0, 0, 100)

            val corners = listOf(
                Point(sel.x, sel.y),
                Point(sel.x + sel.width, sel.y),
                Point(sel.x, sel.y + sel.height),
                Point(sel.x + sel.width, sel.y + sel.height)
            )

            corners.forEach { c ->
                // Shadow
                g2d.color = shadowColor
                g2d.fillRect(c.x - half + 1, c.y - half + 1, handleSize, handleSize)

                // Fill handle
                g2d.color = accentColor
                g2d.fillRect(c.x - half, c.y - half, handleSize, handleSize)

                // Handle border
                g2d.color = borderHandleColor
                g2d.stroke = BasicStroke(1f)
                g2d.drawRect(c.x - half, c.y - half, handleSize, handleSize)
            }

        } ?: run {
            // No selection so draw full overlay
            g2d.fillRect(0, 0, width, height)
        }
    }

    private fun createButtonsPanel(): JPanel {
        return object : JPanel(FlowLayout(FlowLayout.CENTER, 10, 5)) {
            override fun paintComponent(g: Graphics) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val bgColor = UIManager.getColor("Panel.background") ?: Color(40, 40, 40)
                g2d.color = Color(bgColor.red, bgColor.green, bgColor.blue, 200)
                g2d.fillRoundRect(0, 0, width, height, 12, 12)

                val borderColor = UIManager.getColor("Component.borderColor") ?: Color(70, 70, 70)
                g2d.color = Color(borderColor.red, borderColor.green, borderColor.blue, 180)
                g2d.stroke = BasicStroke(1.5f)
                val rect = RoundRectangle2D.Float(
                    0.75f, 0.75f, width - 1.5f, height - 1.5f, 12f, 12f
                )
                g2d.draw(rect)

                super.paintComponent(g)
            }

        }.apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(5, 10, 5, 10)

            add(JButton("Translate").apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { getSelectedImage()?.let(onCapture) }
            })
            add(JButton("Cancel").apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { onCancel() }
            })

            size = preferredSize
        }
    }

    fun calculateButtonsPosition(selection: Rectangle): Point {
        val panelSize = buttonsPanel.preferredSize
        val screenBounds = getVirtualScreenBounds()
        val padding = 10

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

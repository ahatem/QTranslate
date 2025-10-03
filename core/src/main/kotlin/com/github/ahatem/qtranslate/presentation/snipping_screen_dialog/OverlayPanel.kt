package com.github.ahatem.qtranslate.presentation.snipping_screen_dialog

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.utils.copyToClipboard
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.math.abs
import kotlin.math.min

// TODO: need refactor
class OverlayPanel(frame: JFrame, dialog: JDialog) : JPanel() {
    private val screenshot: BufferedImage
    private lateinit var overlay: BufferedImage

    private val listener = OverlayPanelMouseListener(this)

    private val buttonsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        background = Color(0, 0, 0, 0)

        add(JButton("Translate").apply {
            toolTipText = "Translate"
            margin = Insets(5, 5, 5, 5)
            addActionListener {
                dialog.dispose()
                QTranslateViewModel.setInputText(QTranslateViewModel.extractText(getSelectedImage()))
                frame.isVisible = true
                frame.state = JFrame.NORMAL
                WindowKeyListeners.Translate.action.actionPerformed(it)
            }
        })
        add(JButton().apply {
            icon = FlatSVGIcon("app-icons/copy-alt.svg", 16, 16).apply {
                colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
            }
            toolTipText = "Copy Translation"
            addActionListener {
                QTranslateViewModel.extractText(getSelectedImage()).copyToClipboard()
                dialog.dispose()
            }
            margin = Insets(5, 5, 5, 5)
        })
        add(JButton().apply {
            icon = FlatSVGIcon("app-icons/cross.svg", 16, 16).apply {
                colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
            }
            margin = Insets(5, 5, 5, 5)
            toolTipText = "Close"
            addActionListener { dialog.dispose() }
        })
    }

    init {
        val screenSize: Dimension = Toolkit.getDefaultToolkit().screenSize
        val robot = Robot()
        screenshot = robot.createScreenCapture(Rectangle(0, 0, screenSize.width, screenSize.height))
        layout = null

        buttonsPanel.setLocation(-1000, -1000)
        buttonsPanel.size = buttonsPanel.preferredSize

        add(buttonsPanel)

        isOpaque = false
        preferredSize = screenSize
        cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
    }

    fun updateButtonPosition() {
        val buttonX = listener.selection!!.x + listener.selection!!.width - buttonsPanel.width
        val buttonY = listener.selection!!.y + listener.selection!!.height + 10
        buttonsPanel.setLocation(buttonX, buttonY)
        revalidate()
        repaint()
    }

    fun hideButton() {
        buttonsPanel.setLocation(-1000, -1000)
        revalidate()
        repaint()
    }

    fun selectArea(x1: Int, y1: Int, x2: Int, y2: Int) {
        listener.selection = Rectangle(min(x1, x2), min(y1, y2), abs(x2 - x1), abs(y2 - y1))
        reCreateOverlay()
        repaint()
    }

    fun moveSelectedArea(x1: Int, y1: Int, x2: Int, y2: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        listener.selection!!.setLocation(dx, dy)
        reCreateOverlay()
        repaint()
    }

    fun resizeSelectedArea(x1: Int, y1: Int, x2: Int, y2: Int) {
        val deltaX: Int = x2 - x1
        val deltaY: Int = y2 - y1
        when (listener.resizeMode) {
            OverlayPanelMouseListener.ResizeMode.RESIZE_NORTH -> {
                listener.selection!!.y += deltaY
                listener.selection!!.height -= deltaY
            }

            OverlayPanelMouseListener.ResizeMode.RESIZE_SOUTH -> {
                listener.selection!!.height += deltaY
            }

            OverlayPanelMouseListener.ResizeMode.RESIZE_WEST -> {
                listener.selection!!.x += deltaX
                listener.selection!!.width -= deltaX
            }

            OverlayPanelMouseListener.ResizeMode.RESIZE_EAST -> {
                listener.selection!!.width += deltaX
            }

            OverlayPanelMouseListener.ResizeMode.RESIZE_NORTHWEST -> {
                listener.selection!!.x += deltaX
                listener.selection!!.width -= deltaX
                listener.selection!!.y += deltaY
                listener.selection!!.height -= deltaY
            }

            OverlayPanelMouseListener.ResizeMode.RESIZE_NORTHEAST -> {
                listener.selection!!.width += deltaX
                listener.selection!!.y += deltaY
                listener.selection!!.height -= deltaY
            }

            OverlayPanelMouseListener.ResizeMode.RESIZE_SOUTHWEST -> {
                listener.selection!!.x += deltaX
                listener.selection!!.width -= deltaX
                listener.selection!!.height += deltaY
            }

            OverlayPanelMouseListener.ResizeMode.RESIZE_SOUTHEAST -> {
                listener.selection!!.width += deltaX
                listener.selection!!.height += deltaY
            }

            else -> Unit
        }

        if (listener.selection!!.width - OverlayPanelMouseListener.EDGE_SIZE <= 50) {
            listener.selection!!.width = listener.selection!!.width + OverlayPanelMouseListener.EDGE_SIZE
            listener.resizeMode = OverlayPanelMouseListener.ResizeMode.RESIZE_NONE
            return
        } else if (listener.selection!!.height - OverlayPanelMouseListener.EDGE_SIZE <= 50) {
            listener.selection!!.height = listener.selection!!.height + OverlayPanelMouseListener.EDGE_SIZE
            listener.resizeMode = OverlayPanelMouseListener.ResizeMode.RESIZE_NONE
            return
        }


        reCreateOverlay()
        repaint()
    }

    private fun reCreateOverlay() {
        overlay = BufferedImage(screenshot.width, screenshot.height, BufferedImage.TYPE_INT_ARGB).apply {
            val g2d = createGraphics()
            g2d.color = Color(0, 0, 0, 35)
            g2d.fillRect(0, 0, width, height)
            g2d.composite = AlphaComposite.Clear
            g2d.fillRect(
                listener.selection!!.x,
                listener.selection!!.y,
                listener.selection!!.width,
                listener.selection!!.height
            )
            g2d.dispose()
        }
    }


    private fun upscaleImage(originalImage: BufferedImage, scale: Double = 1.0): BufferedImage {
        val newWidth = (originalImage.width * scale).toInt()
        val newHeight = (originalImage.height * scale).toInt()
        val newImage = BufferedImage(newWidth, newHeight, originalImage.type)
        newImage.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(originalImage, 0, 0, newWidth, newHeight, null)
            dispose()
        }
        return newImage
    }

    private fun getSelectedImage(): BufferedImage {
        val inputImage = screenshot.getSubimage(
            listener.selection!!.x,
            listener.selection!!.y,
            listener.selection!!.width,
            listener.selection!!.height
        )
        return upscaleImage(inputImage)
    }

    override fun paint(g: Graphics?) {
        val g2d = g?.create() as Graphics2D

        g2d.drawImage(screenshot, 0, 0, null)
        if (this::overlay.isInitialized) {
            g2d.drawImage(overlay, 0, 0, null)
        } else {
            g2d.color = Color(0, 0, 0, 25)
            g2d.fillRect(0, 0, screenshot.width, screenshot.height)
        }

        if (listener.selection != null) {
            g2d.color = Color.DARK_GRAY
            g2d.stroke = BasicStroke(
                OverlayPanelMouseListener.BORDER_SIZE.toFloat(),
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_BEVEL,
                0f,
                floatArrayOf(3f),
                0f
            )
            g2d.drawRect(
                listener.selection!!.x,
                listener.selection!!.y,
                listener.selection!!.width,
                listener.selection!!.height
            )

            g2d.fillRect(
                listener.selection!!.x - OverlayPanelMouseListener.EDGE_SIZE,
                listener.selection!!.y - OverlayPanelMouseListener.EDGE_SIZE,
                OverlayPanelMouseListener.EDGE_SIZE * 2,
                OverlayPanelMouseListener.EDGE_SIZE * 2
            )
            g2d.fillRect(
                listener.selection!!.x + listener.selection!!.width - OverlayPanelMouseListener.EDGE_SIZE,
                listener.selection!!.y - OverlayPanelMouseListener.EDGE_SIZE,
                OverlayPanelMouseListener.EDGE_SIZE * 2,
                OverlayPanelMouseListener.EDGE_SIZE * 2
            )
            g2d.fillRect(
                listener.selection!!.x - OverlayPanelMouseListener.EDGE_SIZE,
                listener.selection!!.y + listener.selection!!.height - OverlayPanelMouseListener.EDGE_SIZE,
                OverlayPanelMouseListener.EDGE_SIZE * 2,
                OverlayPanelMouseListener.EDGE_SIZE * 2
            )
            g2d.fillRect(
                listener.selection!!.x + listener.selection!!.width - OverlayPanelMouseListener.EDGE_SIZE,
                listener.selection!!.y + listener.selection!!.height - OverlayPanelMouseListener.EDGE_SIZE,
                OverlayPanelMouseListener.EDGE_SIZE * 2,
                OverlayPanelMouseListener.EDGE_SIZE * 2
            )

            val centerLeft = Point(
                listener.selection!!.x,
                listener.selection!!.y + listener.selection!!.height / 2
            )
            val centerTop = Point(
                listener.selection!!.x + listener.selection!!.width / 2,
                listener.selection!!.y
            )
            val centerRight = Point(
                listener.selection!!.x + listener.selection!!.width,
                listener.selection!!.y + listener.selection!!.height / 2
            )
            val centerBottom = Point(
                listener.selection!!.x + listener.selection!!.width / 2,
                listener.selection!!.y + listener.selection!!.height
            )

            g2d.fillRect(
                centerLeft.x - OverlayPanelMouseListener.EDGE_SIZE,
                centerLeft.y - OverlayPanelMouseListener.EDGE_SIZE,
                OverlayPanelMouseListener.EDGE_SIZE * 2,
                OverlayPanelMouseListener.EDGE_SIZE * 2
            )
            g2d.fillRect(
                centerTop.x - OverlayPanelMouseListener.EDGE_SIZE,
                centerTop.y - OverlayPanelMouseListener.EDGE_SIZE,
                OverlayPanelMouseListener.EDGE_SIZE * 2,
                OverlayPanelMouseListener.EDGE_SIZE * 2
            )
            g2d.fillRect(
                centerRight.x - OverlayPanelMouseListener.EDGE_SIZE,
                centerRight.y - OverlayPanelMouseListener.EDGE_SIZE,
                OverlayPanelMouseListener.EDGE_SIZE * 2,
                OverlayPanelMouseListener.EDGE_SIZE * 2
            )
            g2d.fillRect(
                centerBottom.x - OverlayPanelMouseListener.EDGE_SIZE,
                centerBottom.y - OverlayPanelMouseListener.EDGE_SIZE,
                OverlayPanelMouseListener.EDGE_SIZE * 2,
                OverlayPanelMouseListener.EDGE_SIZE * 2
            )
        }
        super.paint(g)
    }
}

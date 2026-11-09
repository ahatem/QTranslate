package com.github.ahatem.qtranslate.ui.swing.ocr

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.ocr.OCRRegionKind
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.ui.swing.snippingtool.CaptureGeometry
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.text.AttributedString
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.pow

/**
 * The workspace's source-image view: zoom, fit/actual size, optional OCR boxes, and click-to-text.
 *
 * The panel owns only presentation. It receives immutable image/region state and reports a clicked
 * region index; the workspace decides what that means for the text editor.
 */
internal class OcrImagePanel : JPanel(), Scrollable {

    var emptyMessage: String = ""
    private var hoveredRegion: Int? = null

    private var image: BufferedImage? = null
    private var regions: List<OCRTextRegion> = emptyList()
    private var annotationsVisible = false
    private var highlightedRegion: Int? = null
    private var scale = 1.0
    private var fitMode = true
    private var lastDrag: Point? = null

    var onRegionClicked: ((Int) -> Unit)? = null
    var onZoomChanged: ((Double) -> Unit)? = null
    var onFileDropped: ((File) -> Unit)? = null

    init {
        isOpaque = true
        background = UIManager.getColor("TextArea.background") ?: UIManager.getColor("Panel.background")
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val next = if (annotationsVisible) regionIndexAt(e.point) else null
                if (hoveredRegion != next) { hoveredRegion = next; repaint() }
                if (lastDrag == null) updateCursor(e.point)
            }
            override fun mouseExited(e: MouseEvent) { hoveredRegion = null; repaint() }
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val hit = if (annotationsVisible) regionIndexAt(e.point) else null
                when {
                    hit != null -> onRegionClicked?.invoke(hit)
                    e.clickCount == 2 && image != null -> toggleFit()
                }
            }
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && canPan()) lastDrag = e.locationOnScreen
            }
            override fun mouseDragged(e: MouseEvent) {
                val last = lastDrag ?: return
                val viewport = parent as? JViewport ?: return
                val now = e.locationOnScreen
                val pos = viewport.viewPosition
                scrollTo(viewport, pos.x + last.x - now.x, pos.y + last.y - now.y)
                lastDrag = now
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }
            override fun mouseReleased(e: MouseEvent) {
                lastDrag = null
                updateCursor(e.point)
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if ((e.isControlDown || e.isMetaDown) && image != null) {
                    fitMode = false
                    setScale(scale * ZOOM_WHEEL.pow(-e.preciseWheelRotation), e.point)
                    e.consume()
                } else {
                    val pane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this@OcrImagePanel)
                        ?: return
                    pane.dispatchEvent(SwingUtilities.convertMouseEvent(this@OcrImagePanel, e, pane))
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)

        // Files are read on the EDT during the drop: the drop Transferable is not reliably
        // valid afterwards, so only the resulting File is handed to the async pipeline.
        transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                }.getOrNull().orEmpty()
                val file = files.firstOrNull { it.isFile } ?: return false
                onFileDropped?.invoke(file)
                return true
            }
        }
    }

    override fun updateUI() {
        super.updateUI()
        background = UIManager.getColor("TextArea.background") ?: UIManager.getColor("Panel.background")
    }

    fun setImage(image: BufferedImage?) {
        this.image = image
        highlightedRegion = null
        hoveredRegion = null
        fitMode = true
        revalidate()
        repaint()
    }

    fun currentImage(): BufferedImage? = image

    fun setRegions(regions: List<OCRTextRegion>) {
        this.regions = regions
        if (highlightedRegion != null && highlightedRegion !in regions.indices) highlightedRegion = null
        repaint()
    }

    fun setAnnotationsVisible(visible: Boolean) {
        annotationsVisible = visible
        repaint()
    }

    fun setHighlightedRegion(index: Int?, reveal: Boolean = false) {
        highlightedRegion = index?.takeIf { it in regions.indices }
        if (reveal) {
            highlightedRegion?.let { scrollRectToVisible(regionViewRect(regions[it]).apply { grow(UIScale.scale(24), UIScale.scale(24)) }) }
        }
        repaint()
    }

    fun zoomIn() {
        fitMode = false
        setScale(scale * ZOOM_STEP)
    }

    fun isFitMode(): Boolean = fitMode

    fun refitIfFitted(viewport: Dimension) {
        if (fitMode) fitToWindow(viewport)
    }

    fun zoomOut() {
        fitMode = false
        setScale(scale / ZOOM_STEP)
    }

    fun fitToWindow(viewport: Dimension) {
        image ?: return
        fitMode = true
        val current = image ?: return
        if (viewport.width <= 0 || viewport.height <= 0) return
        scale = minOf(viewport.width.toDouble() / current.width, viewport.height.toDouble() / current.height, 1.5)
        updatePreferredSize()
        onZoomChanged?.invoke(scale)
    }

    fun actualSize() {
        image ?: return
        fitMode = false
        setScale(1.0)
    }

    fun toggleFit() {
        val viewport = parent as? JViewport ?: return
        if (fitMode) actualSize() else fitToWindow(viewport.extentSize)
    }

    /** Keeps the image point under [anchor] (view coordinates; default: viewport center) fixed. */
    private fun setScale(target: Double, anchor: Point? = null) {
        val next = target.coerceIn(MIN_SCALE, MAX_SCALE)
        if (next == scale) return
        val viewport = parent as? JViewport
        val offset = imageOffset()
        val focus = anchor ?: viewport?.let {
            Point(it.viewPosition.x + it.width / 2, it.viewPosition.y + it.height / 2)
        }
        val imageX = focus?.let { (it.x - offset.x) / scale } ?: 0.0
        val imageY = focus?.let { (it.y - offset.y) / scale } ?: 0.0
        val screenOffset = if (viewport != null && focus != null) {
            Point(focus.x - viewport.viewPosition.x, focus.y - viewport.viewPosition.y)
        } else null

        scale = next
        revalidate()
        repaint()
        onZoomChanged?.invoke(scale)

        if (viewport != null && screenOffset != null) {
            SwingUtilities.invokeLater {
                val o = imageOffset()
                scrollTo(
                    viewport,
                    (o.x + imageX * scale - screenOffset.x).toInt(),
                    (o.y + imageY * scale - screenOffset.y).toInt(),
                )
            }
        }
    }

    private fun scrollTo(viewport: JViewport, x: Int, y: Int) {
        val maxX = max(0, width - viewport.width)
        val maxY = max(0, height - viewport.height)
        viewport.viewPosition = Point(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    private fun canPan(): Boolean {
        val viewport = parent as? JViewport ?: return false
        return width > viewport.width || height > viewport.height
    }

    private fun updateCursor(point: Point) {
        cursor = if (annotationsVisible && regionIndexAt(point) != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun regionViewRect(region: OCRTextRegion): Rectangle {
        val offset = imageOffset()
        val rect = Rectangle(
            region.left, region.top,
            region.right - region.left, region.bottom - region.top,
        )
        return CaptureGeometry.imageRectToView(rect, offset, scale) ?: Rectangle(offset.x, offset.y, 1, 1)
    }

    fun zoomScale(): Double = scale

    override fun getPreferredSize(): Dimension {
        val current = image ?: return Dimension(1, 1)
        return Dimension(
            max(1, (current.width * scale).toInt()),
            max(1, (current.height * scale).toInt()),
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val source = image
        if (source == null) {
            if (emptyMessage.isNotBlank()) {
                val copy = g.create() as Graphics2D
                copy.color = UIManager.getColor("Label.disabledForeground") ?: foreground
                val iterator = AttributedString(emptyMessage).apply { addAttribute(TextAttribute.FONT, font) }.iterator
                val measurer = LineBreakMeasurer(iterator, copy.fontRenderContext)
                val layouts = mutableListOf<java.awt.font.TextLayout>()
                while (measurer.position < iterator.endIndex) {
                    layouts += measurer.nextLayout((width - 16).coerceAtLeast(1).toFloat()) ?: break
                }
                val total = layouts.sumOf { (it.ascent + it.descent + it.leading).toDouble() }.toFloat()
                var y = (height - total) / 2f
                layouts.forEach { line ->
                    y += line.ascent
                    line.draw(copy, (width - line.advance) / 2f, y)
                    y += line.descent + line.leading
                }
                copy.dispose()
            }
            return
        }
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            when {
                scale >= 2.0 -> RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                scale < 1.0 -> RenderingHints.VALUE_INTERPOLATION_BICUBIC
                else -> RenderingHints.VALUE_INTERPOLATION_BILINEAR
            },
        )
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val offset = imageOffset()
        g2d.drawImage(source, offset.x, offset.y, width, height, null)
        g2d.color = UIManager.getColor("Component.borderColor") ?: foreground
        g2d.stroke = BasicStroke(UIScale.scale(1f))
        g2d.drawRect(offset.x, offset.y, width - 1, height - 1)

        if (!annotationsVisible || regions.isEmpty()) { g2d.dispose(); return }
        val normal = UIManager.getColor("Component.focusedBorderColor") ?: Color(0, 120, 215)
        val selected = UIManager.getColor("Component.accentColor") ?: Color.ORANGE
        regions.forEachIndexed { index, region ->
            val view = CaptureGeometry.imageRectToView(
                rect = Rectangle(region.left, region.top, region.right - region.left, region.bottom - region.top),
                imageOffset = offset,
                scale = scale,
            ) ?: return@forEachIndexed
            val isSelected = index == highlightedRegion || index == hoveredRegion
            g2d.color = if (isSelected) selected else Color(normal.red, normal.green, normal.blue, 85)
            g2d.stroke = BasicStroke(UIScale.scale(if (isSelected) 2.0f else 1.0f))
            g2d.drawRect(view.x, view.y, max(1, view.width), max(1, view.height))
            if (isSelected) {
                g2d.color = Color(selected.red, selected.green, selected.blue, 38)
                g2d.fillRect(view.x, view.y, max(1, view.width), max(1, view.height))
            }
        }
        g2d.dispose()
    }

    private fun imageOffset(): Point {
        val size = preferredSize
        return Point(max(0, (width - size.width) / 2), max(0, (height - size.height) / 2))
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableTracksViewportWidth(): Boolean = (parent as? JViewport)?.width?.let { it > preferredSize.width } == true
    override fun getScrollableTracksViewportHeight(): Boolean = (parent as? JViewport)?.height?.let { it > preferredSize.height } == true
    override fun getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int): Int = 16
    override fun getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.HORIZONTAL) max(16, rect.width - 16) else max(16, rect.height - 16)

    private fun updatePreferredSize() {
        revalidate()
        repaint()
    }

    private fun regionIndexAt(point: Point): Int? {
        if (scale <= 0.0) return null
        val offset = imageOffset()
        if (point.x < offset.x || point.y < offset.y) return null
        val imageX = ((point.x - offset.x) / scale).toInt()
        val imageY = ((point.y - offset.y) / scale).toInt()
        // Prefer the finest region under the cursor; line regions are the usual case here.
        return regions.indices
            .filter { index ->
                val region = regions[index]
                imageX in region.left..region.right && imageY in region.top..region.bottom
            }
            .minByOrNull { index ->
                val region = regions[index]
                if (region.kind == OCRRegionKind.WORD) 0 else 1
            }
    }

    private companion object {
        const val ZOOM_STEP = 1.25
        const val ZOOM_WHEEL = 1.15
        const val MIN_SCALE = 0.1
        const val MAX_SCALE = 8.0
    }
}

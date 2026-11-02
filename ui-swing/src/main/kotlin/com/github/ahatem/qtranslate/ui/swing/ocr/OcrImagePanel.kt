package com.github.ahatem.qtranslate.ui.swing.ocr

import com.github.ahatem.qtranslate.api.ocr.OCRRegionKind
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.ui.swing.snippingtool.CaptureGeometry
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.UIManager
import kotlin.math.max

/**
 * The workspace's source-image view: zoom, fit/actual size, optional OCR boxes, and click-to-text.
 *
 * The panel owns only presentation. It receives immutable image/region state and reports a clicked
 * region index; the workspace decides what that means for the text editor.
 */
internal class OcrImagePanel : JPanel() {

    private var image: BufferedImage? = null
    private var regions: List<OCRTextRegion> = emptyList()
    private var annotationsVisible = false
    private var highlightedRegion: Int? = null
    private var scale = 1.0
    private var fitMode = true

    var onRegionClicked: ((Int) -> Unit)? = null

    init {
        isOpaque = true
        background = UIManager.getColor("Panel.background") ?: Color.DARK_GRAY
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!annotationsVisible || regionIndexAt(e.point) == null) return
                regionIndexAt(e.point)?.let { onRegionClicked?.invoke(it) }
            }
        })
    }

    fun setImage(image: BufferedImage?) {
        this.image = image
        highlightedRegion = null
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

    fun setHighlightedRegion(index: Int?) {
        highlightedRegion = index?.takeIf { it in regions.indices }
        repaint()
    }

    fun zoomIn() {
        fitMode = false
        scale = (scale * ZOOM_STEP).coerceAtMost(MAX_SCALE)
        updatePreferredSize()
    }

    fun isFitMode(): Boolean = fitMode

    fun refitIfFitted(viewport: Dimension) {
        if (fitMode) fitToWindow(viewport)
    }

    fun zoomOut() {
        fitMode = false
        scale = (scale / ZOOM_STEP).coerceAtLeast(MIN_SCALE)
        updatePreferredSize()
    }

    fun fitToWindow(viewport: Dimension) {
        image ?: return
        fitMode = true
        scale = CaptureGeometry.fitScale(
            Dimension(image!!.width, image!!.height),
            viewport,
        )
        updatePreferredSize()
    }

    fun actualSize() {
        image ?: return
        fitMode = false
        scale = 1.0
        updatePreferredSize()
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
        val source = image ?: return
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        g2d.drawImage(source, 0, 0, width, height, null)

        if (!annotationsVisible || regions.isEmpty()) return
        val normal = UIManager.getColor("Component.focusedBorderColor") ?: Color(0, 120, 215)
        val selected = UIManager.getColor("Component.accentColor") ?: Color.ORANGE
        regions.forEachIndexed { index, region ->
            val view = CaptureGeometry.imageRectToView(
                rect = Rectangle(region.left, region.top, region.right - region.left, region.bottom - region.top),
                imageOffset = Point(0, 0),
                scale = scale,
            ) ?: return@forEachIndexed
            val isSelected = index == highlightedRegion
            g2d.color = if (isSelected) selected else Color(normal.red, normal.green, normal.blue, 150)
            g2d.stroke = BasicStroke(if (isSelected) 2.5f else 1.2f)
            g2d.drawRect(view.x, view.y, max(1, view.width), max(1, view.height))
            if (isSelected) {
                g2d.color = Color(selected.red, selected.green, selected.blue, 38)
                g2d.fillRect(view.x, view.y, max(1, view.width), max(1, view.height))
            }
        }
    }

    private fun updatePreferredSize() {
        revalidate()
        repaint()
    }

    private fun regionIndexAt(point: Point): Int? {
        if (scale <= 0.0) return null
        val imageX = (point.x / scale).toInt()
        val imageY = (point.y / scale).toInt()
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
        const val MIN_SCALE = 0.1
        const val MAX_SCALE = 8.0
    }
}

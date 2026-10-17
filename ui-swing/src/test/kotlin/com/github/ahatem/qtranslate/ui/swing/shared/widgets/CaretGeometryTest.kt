package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.ui.swing.shared.textpane.CaretGeometry
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import javax.swing.text.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The caret has to be drawn on the display's pixel grid.
 *
 * A caret drawn as a stroked line is centred on its coordinate and spans half a pixel either side, so
 * its outer columns are only partly covered. Under a fractional display scale the rasteriser has to
 * quantise the requested width to a whole number of device pixels, and which number it picks depends
 * on the caret's subpixel position, so the caret changes apparent thickness as it moves along a line
 * and its edges go soft. These tests pin the properties that remove that: a whole number of device
 * columns, the same number at every position, no partly covered column, agreement with the boundary
 * the editor hit-tests against, and a height that follows the font rather than a fixed inset.
 *
 * The scales are synthetic. Nothing here depends on the display the test machine happens to have.
 */
class CaretGeometryTest {

    private val scales = listOf(1.0, 1.25, 1.5, 1.75, 2.0)

    /** The thickness each scale is expected to produce, in whole device pixels. */
    private val expectedThickness = mapOf(1.0 to 2, 1.25 to 3, 1.5 to 3, 1.75 to 4, 2.0 to 4)

    private fun transform(scale: Double) = AffineTransform.getScaleInstance(scale, scale)

    private fun place(scale: Double, rowX: Double = 40.0, rowHeight: Double = 29.0, ascent: Int = 20, descent: Int = 5) =
        CaretGeometry.place(rowX, 6.0, rowHeight, ascent, descent, transform(scale))

    @Test
    fun `the caret is two device pixels at 100 percent and stays proportionate as the display grows`() {
        // Three whole device pixels at 100% is what read as heavy. It also tripled through the
        // display scale, so the caret was twelve device pixels wide at 200%.
        scales.forEach { scale ->
            assertEquals(
                expectedThickness.getValue(scale),
                place(scale).width,
                "at ${scale * 100}% the caret should be ${expectedThickness.getValue(scale)} device pixels"
            )
        }
    }

    @Test
    fun `thickness does not change as the caret moves along the line`() {
        scales.forEach { scale ->
            val widths = (0..79).map { place(scale, rowX = it.toDouble()).width }.toSet()
            assertEquals<Int>(
                1,
                widths.size,
                "at ${scale * 100}% the caret changed thickness as it moved: saw $widths"
            )
        }
    }

    @Test
    fun `the caret lands on the device column nearest the boundary the editor hit-tests against`() {
        scales.forEach { scale ->
            (0..79).forEach { logicalX ->
                assertEquals<Int>(
                    Math.round(logicalX * scale).toInt(),
                    place(scale, rowX = logicalX.toDouble()).x,
                    "at ${scale * 100}% a caret at user x=$logicalX should snap to the nearest device column"
                )
            }
        }
    }

    @Test
    fun `the caret never leaves the row it belongs to`() {
        // The row is what Swing repaints when the caret blinks, so anything drawn outside it can
        // survive a blink and leave a trail.
        scales.forEach { scale ->
            listOf(11 to (12 to 3), 19 to (20 to 5), 36 to (37 to 8)).forEach { (size, metrics) ->
                val rowHeight = size * 1.5
                val placement = place(scale, rowHeight = rowHeight, ascent = metrics.first, descent = metrics.second)
                val rowTop = Math.round(6.0 * scale)
                val rowBottom = Math.round((6.0 + rowHeight) * scale)
                assertTrue(
                    placement.y >= rowTop,
                    "at ${scale * 100}% size $size the caret starts at ${placement.y}, above the row at $rowTop"
                )
                assertTrue(
                    placement.y + placement.height <= rowBottom,
                    "at ${scale * 100}% size $size the caret ends at ${placement.y + placement.height}, " +
                        "below the row at $rowBottom"
                )
            }
        }
    }

    @Test
    fun `a row that cannot hold the font is clipped to the row rather than overflowing it`() {
        // A row measured from a smaller run font can be shorter than the component's own font. The
        // caret must stay inside the row all the same, because that is the area that gets repainted.
        scales.forEach { scale ->
            listOf(4.0, 8.5, 17.0, 25.0).forEach { rowHeight ->
                val placement = place(scale, rowHeight = rowHeight, ascent = 37, descent = 8)
                val rowTop = Math.round(6.0 * scale)
                val rowBottom = Math.round((6.0 + rowHeight) * scale)
                assertTrue(placement.height >= 1, "at ${scale * 100}% a $rowHeight row gave a caret of no height")
                assertTrue(
                    placement.y >= rowTop && placement.y + placement.height <= rowBottom,
                    "at ${scale * 100}% a $rowHeight row put the caret at ${placement.y}.." +
                        "${placement.y + placement.height}, outside the row at $rowTop..$rowBottom"
                )
            }
        }
    }

    @Test
    fun `height follows the font rather than a fixed inset`() {
        // A fixed inset is wrong at both ends: it swallows small text and overshoots large text.
        scales.forEach { scale ->
            listOf(11 to (12 to 3), 19 to (20 to 5), 36 to (37 to 8)).forEach { (size, metrics) ->
                val placement = place(scale, rowHeight = size * 1.5, ascent = metrics.first, descent = metrics.second)
                val expected = Math.round((metrics.first + metrics.second) * scale).toInt()
                assertEquals(
                    expected,
                    placement.height,
                    "at ${scale * 100}% size $size the caret should be as tall as the font's own body"
                )
            }
        }
    }

    @Test
    fun `the caret does not change height between neighbouring positions`() {
        scales.forEach { scale ->
            val heights = (0..20).map { place(scale, rowX = it.toDouble()).height }.toSet()
            assertEquals<Int>(1, heights.size, "at ${scale * 100}% the caret height varied by position: $heights")
        }
    }

    @Test
    fun `a degenerate row or font still yields a drawable caret`() {
        listOf(0.0, -1.0, 1.0).forEach { rowHeight ->
            listOf(0 to 0, 20 to 5).forEach { (ascent, descent) ->
                val placement = place(1.5, rowHeight = rowHeight, ascent = ascent, descent = descent)
                assertTrue(placement.width >= 1, "width was ${placement.width}")
                assertTrue(placement.height >= 1, "height was ${placement.height}")
            }
        }
    }

    @Test
    fun `a transform that collapses the display is treated as one to one`() {
        val placement = CaretGeometry.place(40.0, 6.0, 29.0, 20, 5, AffineTransform(0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertTrue(placement.width >= 1, "a collapsed transform must not produce a caret of no width")
        assertTrue(placement.height >= 1, "a collapsed transform must not produce a caret of no height")
    }

    @Test
    fun `the drawing transform is one to one on a translation that is not a whole number`() {
        val offset = AffineTransform.getScaleInstance(1.5, 1.5)
        offset.translate(3.4, 7.6)
        val drawing = CaretGeometry.transformFor(CaretGeometry.place(40.0, 6.0, 29.0, 20, 5, offset))

        assertEquals(1.0, drawing.scaleX, 1e-9, "the scale must be dropped so the rectangle keeps whole edges")
        assertEquals(1.0, drawing.scaleY, 1e-9, "the scale must be dropped so the rectangle keeps whole edges")

        val placement = CaretGeometry.place(40.0, 6.0, 29.0, 20, 5, offset)
        val corner = Point2D.Double(placement.x.toDouble(), placement.y.toDouble())
        drawing.transform(corner, corner)
        assertEquals(Math.floor(corner.x), corner.x, 1e-9, "the caret's left edge must land on a whole device pixel")
        assertEquals(Math.floor(corner.y), corner.y, 1e-9, "the caret's top edge must land on a whole device pixel")
    }
}

/**
 * The same properties, measured off a real painted caret rather than off the arithmetic.
 *
 * A real pane lays the row out and the real caret reads its geometry, so this catches a caret that is
 * correct on paper but painted with the wrong transform. The caret is painted on its own over a blank
 * image, so the measurement is the caret's and not the text's.
 */
class CaretPaintingTest {

    private val scales = listOf(1.0, 1.25, 1.5, 1.75, 2.0)

    /** Device thickness expected at each scale. */
    private val expectedThickness = mapOf(1.0 to 2, 1.25 to 3, 1.5 to 3, 1.75 to 4, 2.0 to 4)

    private val text = "The quick brown fox jumps over the lazy dog"

    private fun pane(size: Int = 19, width: Int = 320): AdvancedTextPane {
        val pane = ShapedText.onEdt {
            AdvancedTextPane(onTextChanged = {}, onTranslateRequest = {}, onListenRequest = {})
        }
        ShapedText.onEdt { JScrollPane(pane) }
        // updateFontsAndRescanDocument defers its work with invokeLater, so it needs its own turn
        // before the pane is sized and measured.
        ShapedText.onEdt {
            pane.render(text, emptyList(), isEditable = true)
            pane.updateFontsAndRescanDocument(Font("Serif", Font.PLAIN, size), Font("Serif", Font.PLAIN, size))
        }
        ShapedText.onEdt { pane.setSize(width, 300) }
        ShapedText.awaitFontRuns(pane)
        ShapedText.onEdt {
            pane.validate()
            pane.caret.isVisible = true
        }
        return pane
    }

    /** What the caret painted: the device columns it covers, and how many were partly covered. */
    private data class Painted(val columns: IntRange, val partial: Int, val top: Int, val bottom: Int) {
        val width: Int get() = columns.count()
    }

    /**
     * Paints the caret on its own and reports what it covered.
     *
     * The image is only as tall as the caret's row plus a margin, so a sweep over every caret position
     * at every scale stays quick instead of walking the whole pane pixel by pixel.
     */
    private fun painted(
        pane: AdvancedTextPane,
        dot: Int,
        scale: Double,
        bias: Position.Bias = Position.Bias.Forward,
    ): Painted {
        val row = ShapedText.onEdt { pane.ui.modelToView2D(pane, dot, bias)!! }
        // The image covers just the row's band, in absolute device rows, so a sweep over every caret
        // position at every scale stays quick instead of walking the whole pane pixel by pixel.
        val offset = Math.floor(row.y * scale).toInt() - 2
        val height = Math.ceil((row.y + row.height) * scale).toInt() + 4
        val width = Math.ceil(pane.width * scale).toInt() + 8

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics() as Graphics2D
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.transform = AffineTransform.getScaleInstance(scale, scale)
        ShapedText.onEdt {
            (pane.caret as javax.swing.text.DefaultCaret).setDot(dot, bias)
            pane.caret.paint(g)
        }
        g.dispose()

        var left = -1
        var right = -1
        var partial = 0
        var inkTop = Int.MAX_VALUE
        var inkBottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = image.getRGB(x, y)
                if (rgb != Color.WHITE.rgb) {
                    if (left < 0 || x < left) left = x
                    if (x > right) right = x
                    if (inkTop == Int.MAX_VALUE) inkTop = y
                    inkBottom = y
                    if ((rgb ushr 24) != 255) partial++
                }
            }
        }
        check(left >= 0) { "the caret painted nothing at scale $scale, dot $dot" }
        // Image rows are relative to the band that was allocated, which starts two device rows above
        // the row itself.
        return Painted(left..right, partial, inkTop + offset, inkBottom + offset)
    }

    @Test
    fun `a painted caret covers whole device columns and nothing else`() {
        val pane = pane()
        scales.forEach { scale ->
            val result = painted(pane, 12, scale)
            assertEquals<Int>(0, result.partial, "at ${scale * 100}% the caret left partly covered pixels: ${result.columns}")
        }
    }

    @Test
    fun `a painted caret is two device pixels at 100 percent and stays proportionate as the display grows`() {
        val pane = pane()
        scales.forEach { scale ->
            val width = painted(pane, 12, scale).width
            assertEquals(
                expectedThickness.getValue(scale),
                width,
                "at ${scale * 100}% the caret should be ${expectedThickness.getValue(scale)} device pixels"
            )
        }
    }

    @Test
    fun `a painted caret is thin enough to read as a caret rather than a bar`() {
        // The reported symptom. Three whole device pixels at 100% is what made it look bold.
        assertTrue(painted(pane(), 12, 1.0).width <= 2, "at 100% the caret is too wide to read as thin")
    }

    @Test
    fun `a painted caret is the same thickness wherever it is on the line`() {
        val pane = pane()
        scales.forEach { scale ->
            val widths = (1 until text.length).map { painted(pane, it, scale).width }.toSet()
            assertEquals<Int>(
                1,
                widths.size,
                "at ${scale * 100}% the caret changed thickness as it moved along the line: $widths"
            )
        }
    }

    @Test
    fun `a painted caret starts on the column nearest the boundary the editor reports`() {
        val pane = pane()
        scales.forEach { scale ->
            listOf(3, 12, 21, 30).forEach { dot ->
                val row = ShapedText.onEdt { pane.ui.modelToView2D(pane, dot, Position.Bias.Forward)!! }
                val result = painted(pane, dot, scale)
                assertEquals<Int>(
                    Math.round(row.x * scale).toInt(),
                    result.columns.first,
                    "at ${scale * 100}% the caret at $dot does not sit on the boundary the editor reports"
                )
            }
        }
    }

    @Test
    fun `a painted caret sits on the boundary the caret's own bias asks for`() {
        // Where two graphemes meet, Forward and Backward resolve to different boundaries. The caret has
        // to sit on the one it reports, because that is the one a click is answered against.
        val pane = pane()
        scales.forEach { scale ->
            listOf(Position.Bias.Forward, Position.Bias.Backward).forEach { bias ->
                listOf(3, 12, 21).forEach { dot ->
                    val row = ShapedText.onEdt { pane.ui.modelToView2D(pane, dot, bias)!! }
                    val result = painted(pane, dot, scale, bias)
                    assertEquals<Int>(
                        Math.round(row.x * scale).toInt(),
                        result.columns.first,
                        "at ${scale * 100}% the caret at $dot with a $bias bias does not sit on the " +
                            "boundary the editor reports for that bias"
                    )
                }
            }
        }
    }

    @Test
    fun `a painted caret stays within the row at every font size`() {
        listOf(11, 15, 19, 28).forEach { size ->
            val pane = pane(size = size)
            scales.forEach { scale ->
                val result = painted(pane, 12, scale)
                val row = ShapedText.onEdt { pane.ui.modelToView2D(pane, 12, Position.Bias.Forward)!! }
                val rowTop = Math.round(row.y * scale)
                assertTrue(
                    result.top >= rowTop,
                    "size $size at ${scale * 100}% the caret starts at ${result.top}, above the row at $rowTop"
                )
            }
        }
    }
}
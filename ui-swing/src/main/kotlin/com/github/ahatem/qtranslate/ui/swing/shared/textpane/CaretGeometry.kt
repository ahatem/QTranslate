package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import java.awt.geom.AffineTransform
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where the caret goes, resolved in whole device pixels.
 *
 * Pure arithmetic on the geometry the text UI reported and the display's scale, with no painting in
 * it, so the policy can be checked directly at every scale factor rather than only through rendered
 * pixels. [place] answers in device space; [transformFor] gives the transform that draws it.
 *
 * Three decisions live here, and each of them is what makes a caret look crisp.
 *
 * **Fill, not stroke.** A stroked line is centred on its coordinate, so it spans half a pixel to
 * either side and its outer columns are only partly covered. With smoothing off, a width that lands
 * between two columns is decided by a tie-break rather than by geometry, which is how a caret ends up
 * looking soft or one pixel wider than it should. A rectangle has no such ambiguity.
 *
 * **Round after scaling.** The text UI reports user-space coordinates, and rounding those rounds in
 * user space. Under a fractional display scale that is not the same as landing on a device pixel: a
 * user-space x of 41 is a device x of 61.5 at 150%, and rounding it to 41 before scaling leaves the
 * caret straddling two columns for good. The scale is applied first and the result rounded.
 *
 * **A width that does not depend on where the caret is.** The old code asked for a pen three user
 * units wide, which the rasteriser then had to quantise to a whole number of device pixels. That
 * number came out as 3 at 100%, and as 4 or 5 at 125% depending on the caret's subpixel position, so
 * the caret visibly changed thickness as it moved. Deriving the width from a rounded edge instead
 * makes it a constant of the display rather than of the caret's position.
 */
internal object CaretGeometry {

    /**
     * Nominal thickness in user units, before the display's scale is applied.
     *
     * Two is what mainstream editors ship: Blink's caret is one and can vanish against a dark
     * background, and three is three whole device pixels at 100%, which is the "too bold" this
     * replaces. Under this policy it comes out as 2, 3, 3, 4 and 4 device pixels at 100%, 125%, 150%,
     * 175% and 200%.
     */
    const val NOMINAL_WIDTH = 2

    /** A caret resolved onto the device pixel grid. */
    data class Placement(
        /** Left edge, in device pixels. */
        val x: Int,
        /** Top edge, in device pixels. */
        val y: Int,
        /** Thickness, in device pixels. Never zero. */
        val width: Int,
        /** Height, in device pixels. Never zero. */
        val height: Int,
        /** The display's translation, rounded down to whole device pixels. */
        val originX: Int,
        val originY: Int,
    )

    /**
     * Resolves the caret at ([rowX], [rowY]) within a row [rowHeight] tall, under [transform].
     *
     * The vertical extent is the font's own body, [ascent] plus [descent], centred in the row. The row
     * is taller than that because the paragraph adds line spacing, and centring keeps the caret from
     * riding higher as the text grows. It comes from one font for the whole component rather than
     * from the run under the caret: a run drawn in a fallback face has its own metrics, and following
     * them would resize the caret every time it crossed a run boundary.
     *
     * The row is a hard limit in both directions. The row rect is what Swing repaints when the caret
     * blinks, so a caret taller than it would leave a trail. That happens when a row is measured from
     * a run whose font is smaller than the component's, which is briefly true after a font change
     * because the rescan that re-measures the rows is deferred.
     */
    fun place(
        rowX: Double,
        rowY: Double,
        rowHeight: Double,
        ascent: Int,
        descent: Int,
        transform: AffineTransform,
        nominalWidth: Int = NOMINAL_WIDTH,
    ): Placement {
        val scaleX = if (transform.scaleX > 0.0) transform.scaleX else 1.0
        val scaleY = if (transform.scaleY > 0.0) transform.scaleY else 1.0

        // Whole device pixels, measured from the display's origin rather than from the row's. Rounding
        // the two edges of the row and taking the difference keeps the caret inside the row even when
        // the two roundings do not add up to the rounded whole.
        val originX = Math.floor(transform.translateX).toInt()
        val originY = Math.floor(transform.translateY).toInt()
        val rowLeft = Math.round(rowX * scaleX + transform.translateX).toInt() - originX
        val rowTop = Math.round(rowY * scaleY + transform.translateY).toInt() - originY
        val rowBottom = Math.round((rowY + rowHeight) * scaleY + transform.translateY).toInt() - originY

        val body = (ascent + descent).coerceAtLeast(1)
        val bodyHeight = Math.round(body * scaleY).toInt().coerceAtLeast(1)
        val room = rowBottom - rowTop
        val top = if (bodyHeight >= room) rowTop else rowTop + (room - bodyHeight) / 2
        val bottom = minOf(rowTop + room, top + bodyHeight)

        return Placement(
            x = rowLeft,
            y = top,
            width = max(1, Math.round(nominalWidth * scaleX).toInt()),
            height = max(1, bottom - top),
            originX = originX,
            originY = originY,
        )
    }

    /**
     * The transform that maps [Placement] device pixels onto the display one to one.
     *
     * Java2D only takes its exact integer rectangle path when there is no scale in the transform, so
     * the scale is dropped and the translation kept. Rounding the translation down is what lets the
     * placement above be expressed as whole device pixels in the first place.
     */
    fun transformFor(placement: Placement): AffineTransform =
        AffineTransform(1.0, 0.0, 0.0, 1.0, placement.originX.toDouble(), placement.originY.toDouble())
}
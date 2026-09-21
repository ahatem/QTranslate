package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The selection button's body must grow with the same factor its icon does.
 *
 * `FlatSVGIcon.getIconWidth()` is `UIScale.scale(ICON_SIZE)`, so at 125% the icon is 20px
 * while an unscaled face stays 30px (16/30 becomes 20/30; at 200% the 32px icon overflows
 * the face entirely). Geometry is therefore derived through one injected scale function —
 * mirroring `UIScale.scale(int)` rounding — so every DPI setting keeps the designed
 * icon-to-face proportion without depending on the machine the test runs on.
 */
class SelectionTranslateButtonGeometryTest {

    /** Same rounding `UIScale.scale(int)` applies: round half up after multiplying. */
    private fun at(factor: Double): (Int) -> Int = { Math.round(it * factor).toInt() }

    @Test
    fun `identity scale keeps authored constants`() {
        val geo = SelectionTranslateButton.geometry(at(1.0))
        assertEquals(42, geo.edge)
        assertEquals(6, geo.padding)
        assertEquals(30, geo.face)
        assertEquals(10, geo.arc)
        assertEquals(14, geo.gap)
        assertEquals(1, geo.shadowOffset)
    }

    @Test
    fun `125 percent scales every dimension`() {
        val geo = SelectionTranslateButton.geometry(at(1.25))
        assertEquals(8, geo.padding)
        assertEquals(38, geo.face)
        assertEquals(54, geo.edge)
        assertEquals(13, geo.arc)
        assertEquals(18, geo.gap)
        assertEquals(1, geo.shadowOffset)
    }

    @Test
    fun `150 and 200 percent scale every dimension`() {
        val half = SelectionTranslateButton.geometry(at(1.5))
        assertEquals(9, half.padding)
        assertEquals(45, half.face)
        assertEquals(63, half.edge)

        val double = SelectionTranslateButton.geometry(at(2.0))
        assertEquals(12, double.padding)
        assertEquals(60, double.face)
        assertEquals(84, double.edge)
        assertEquals(20, double.arc)
        assertEquals(28, double.gap)
    }

    @Test
    fun `window edge always fits face plus shadow room`() {
        // Independent rounding could leave scale(42) one pixel short of the shadow's reach;
        // the edge is composed so the outermost shadow layer is never clipped.
        for (factor in listOf(1.0, 1.25, 1.5, 1.75, 2.0)) {
            val geo = SelectionTranslateButton.geometry(at(factor))
            assertEquals(
                geo.padding + geo.face + geo.padding, geo.edge,
                "edge must fit face plus shadow room at scale $factor",
            )
        }
    }

    @Test
    fun `scaled icon always fits inside scaled face`() {
        // The reported bug: the icon scales (16 -> 20 -> 24 -> 32) while the face did not.
        for (factor in listOf(1.0, 1.25, 1.5, 1.75, 2.0)) {
            val scale = at(factor)
            val geo = SelectionTranslateButton.geometry(scale)
            val icon = scale(SelectionTranslateButton.ICON_SIZE)
            assertTrue(
                icon < geo.face,
                "icon $icon must fit in face ${geo.face} at scale $factor",
            )
        }
    }

    @Test
    fun `icon to face proportion survives scaling`() {
        val designed = SelectionTranslateButton.ICON_SIZE.toDouble() / SelectionTranslateButton.FACE_SIZE
        for (factor in listOf(1.0, 1.25, 1.5, 1.75, 2.0)) {
            val scale = at(factor)
            val geo = SelectionTranslateButton.geometry(scale)
            val ratio = scale(SelectionTranslateButton.ICON_SIZE).toDouble() / geo.face
            assertTrue(
                abs(ratio - designed) < 0.06,
                "ratio $ratio drifted from designed $designed at scale $factor",
            )
        }
    }
}

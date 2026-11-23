package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Geometry derives from one injected scale function, so the icon, face and shadow grow together at every DPI. */
class SelectionTranslateButtonGeometryTest {

    /** Same rounding `UIScale.scale(int)` applies: round half up after multiplying. */
    private fun at(factor: Double): (Int) -> Int = { Math.round(it * factor).toInt() }

    @Test
    fun `window edge always fits face plus shadow room`() {
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

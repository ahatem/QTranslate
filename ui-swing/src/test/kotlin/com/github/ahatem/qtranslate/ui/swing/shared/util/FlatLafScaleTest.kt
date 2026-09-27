package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.util.UIScale
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A test using [withFlatLafScale] must leave later test classes the UI state it found. */
class FlatLafScaleTest {

    private data class UiState(val laf: String, val defaultFont: Any?, val scale: Float)

    private fun current() = UiState(
        laf = UIManager.getLookAndFeel().javaClass.name,
        defaultFont = UIManager.get("defaultFont"),
        scale = UIScale.getUserScaleFactor()
    )

    @Test
    fun `previous look and feel, font and scale are restored`() {
        val before = current()
        withFlatLafScale(fontSize = 48) {
            assertTrue(UIScale.getUserScaleFactor() > 1f, "the raised scale was never applied")
        }
        assertEquals(before, current())
    }

    @Test
    fun `state is restored when the block fails`() {
        val before = current()
        assertFailsWith<IllegalStateException> {
            withFlatLafScale(fontSize = 48) { error("assertion failed inside the block") }
        }
        assertEquals(before, current())
    }
}

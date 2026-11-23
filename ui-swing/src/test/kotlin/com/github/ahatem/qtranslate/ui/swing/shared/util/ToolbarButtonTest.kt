package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolbarButtonTest {
    private val glyph = ImageIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))

    @Test
    fun `click callback fires only while enabled`() = onEdt {
        var clicks = 0
        val button = createToolbarButton(glyph, "Copy") { clicks++ }
        button.doClick(0)
        assertEquals(1, clicks)
        button.isEnabled = false
        button.doClick(0)
        assertEquals(1, clicks)
    }

    @Test
    fun `installs no application mouse listeners under either flatlaf theme`() {
        val original = UIManager.getLookAndFeel()
        try {
            listOf(FlatDarkLaf(), FlatLightLaf()).forEach { laf ->
                onEdt {
                    UIManager.setLookAndFeel(laf)
                    val button = createToolbarButton(glyph, "Copy") {}
                    val owners = (button.mouseListeners.toList() + button.mouseMotionListeners.toList())
                        .map { it.javaClass.name }
                    assertTrue(
                        owners.none { it.startsWith("com.github.ahatem") },
                        "hover must come from the look and feel, found $owners"
                    )
                }
            }
        } finally {
            onEdt { UIManager.setLookAndFeel(original) }
        }
    }
}

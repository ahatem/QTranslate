package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolbarButtonTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private val glyph = ImageIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))

    @Test
    fun `uses the flatlaf toolbar button type`() = onEdt {
        val button = createToolbarButton(glyph, "Copy")
        assertEquals(
            FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON,
            button.getClientProperty(FlatClientProperties.BUTTON_TYPE)
        )
    }

    @Test
    fun `leaves the content area to flatlaf so hover and pressed can paint`() = onEdt {
        val button = createToolbarButton(glyph, "Copy")
        assertTrue(button.isContentAreaFilled)
        assertTrue(button.isBorderPainted)
        assertTrue(button.isFocusPainted)
    }

    @Test
    fun `is keyboard focusable`() = onEdt {
        assertTrue(createToolbarButton(glyph, "Copy").isFocusable)
    }

    @Test
    fun `keeps icon and tooltip`() = onEdt {
        val button = createToolbarButton(glyph, "Copy")
        assertSame(glyph, button.icon)
        assertEquals("Copy", button.toolTipText)
    }

    @Test
    fun `enabled and disabled states work`() = onEdt {
        val button = createToolbarButton(glyph, "Copy")
        assertTrue(button.isEnabled)
        button.isEnabled = false
        assertFalse(button.isEnabled)
    }

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
    fun `icon can change later and selection is model state`() = onEdt {
        val button = createToolbarButton(glyph, "Listen")
        val other = ImageIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
        button.icon = other
        assertSame(other, button.icon)
        button.isSelected = true
        assertTrue(button.model.isSelected)
        assertTrue(button.isContentAreaFilled)
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

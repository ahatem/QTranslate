package com.github.ahatem.qtranslate.ui.swing.ocr

import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OcrAcquisitionBarTest {
    @Test
    fun `overflow retains hidden acquisition and secondary actions with enabled state`() {
        SwingUtilities.invokeAndWait {
            val calls = mutableListOf<String>()
            val labels = OcrAcquisitionBar.Labels("Capture", "Open", "Paste", "Copy", "Save", "Settings", "More")
            val bar = OcrAcquisitionBar(labels, { calls += "capture" }, { calls += "open" },
                { calls += "paste" }, { calls += "copy" }, { calls += "save" }, { calls += "settings" })
            bar.updateForWidth(40)
            assertFalse(bar.open.isVisible)
            assertFalse(bar.paste.isVisible)
            val emptyMenu = bar.menu()
            val items = emptyMenu.components.filterIsInstance<JMenuItem>()
            assertEquals(listOf("Open", "Paste", "Copy", "Save", "Settings"), items.map { it.text })
            assertFalse(items[2].isEnabled)
            assertFalse(items[3].isEnabled)
            items[0].doClick(); items[1].doClick(); items[4].doClick()
            bar.setImageAvailable(true)
            val enabledItems = bar.menu().components.filterIsInstance<JMenuItem>()
            enabledItems[2].doClick(); enabledItems[3].doClick()
            assertEquals(listOf("open", "paste", "settings", "copy", "save"), calls)
            bar.updateForWidth(2000)
            assertTrue(bar.open.isVisible)
            assertTrue(bar.paste.isVisible)
            assertEquals(listOf("Copy", "Save", "Settings"), bar.menu().components.filterIsInstance<JMenuItem>().map { it.text })
        }
    }
}

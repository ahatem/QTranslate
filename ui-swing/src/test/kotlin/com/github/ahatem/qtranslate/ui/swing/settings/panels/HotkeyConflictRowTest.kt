package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JTextArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The recorder dialog is packed around the warning row, so its height must not follow the live text. */
class HotkeyConflictRowTest {

    private val longWarning =
        "⚠  Already assigned to Screen OCR — Default Version. You can still save it."

    private fun row() = HotkeyConflictRow(Color.ORANGE, Font("Dialog", Font.PLAIN, 12))

    private fun messageOf(row: HotkeyConflictRow) =
        row.components.filterIsInstance<JTextArea>().single()

    @Test
    fun `the reserved height holds a real warning`() {
        val row = row()
        val reserved = onEdt {
            row.setSize(Dimension(WIDTH, 100))
            row.preferredSize.height
        }

        val withWarning = onEdt {
            row.show(longWarning)
            row.setSize(Dimension(WIDTH, 100))
            row.preferredSize.height
        }

        assertEquals(
            reserved,
            withWarning,
            "the dialog is packed around this row, so its height cannot follow the live text",
        )
        assertTrue(
            reserved >= onEdt { messageOf(row).preferredSize.height },
            "the reserved height holds the wrapped warning: reserved=$reserved",
        )
    }

    private companion object {
        const val WIDTH = 266
    }
}

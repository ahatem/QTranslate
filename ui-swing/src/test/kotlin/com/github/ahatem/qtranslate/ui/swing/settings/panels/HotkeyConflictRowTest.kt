package com.github.ahatem.qtranslate.ui.swing.settings.panels

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JFrame
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recorder's warning row keeps its place whether or not it has something to say.
 *
 * The dialog is packed around this row, so a row that claimed one line while rendering two left
 * the second line outside the dialog's edge — the warning was cut off mid-sentence exactly when
 * the user needed to read it.
 */
class HotkeyConflictRowTest {

    private val longWarning =
        "⚠  Already assigned to Screen OCR — Default Version. You can still save it."

    private fun row() = HotkeyConflictRow(Color.ORANGE, Font("Dialog", Font.PLAIN, 12))

    private fun messageOf(row: HotkeyConflictRow) =
        row.components.filterIsInstance<JTextArea>().single()

    @Test
    fun `an empty row still reserves a line`() {
        val row = row()
        val reserved = onEdt { row.preferredSize.height }

        onEdt { row.show(null) }

        assertTrue(reserved > 0, "an empty row keeps its height: $reserved")
        assertEquals(
            reserved,
            onEdt { row.preferredSize.height },
            "showing nothing does not change the reserved height",
        )
    }

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

    @Test
    fun `the warning wraps rather than widening the dialog`() {
        val row = row()

        val wraps = onEdt {
            row.show(longWarning)
            messageOf(row).lineWrap
        }

        assertTrue(wraps, "the message wraps")
    }

    private companion object {
        const val WIDTH = 266
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}

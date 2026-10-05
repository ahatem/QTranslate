package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Drives a real mouse drag over a text component.
 *
 * Selection is the platform's own, so a test that wants to see it has to deliver the events a
 * pointer delivers, dispatched exactly as AWT would: whatever ends up selected was selected by the
 * component's UI rather than by the test reaching past it.
 */
internal object SelectionMouseDrag {

    private const val NO_BUTTON = 0

    /**
     * Presses at [fromX], drags to [toX] and releases, all on the text's first line.
     *
     * Component-relative, like every real [MouseEvent], and on the line of text rather than
     * anywhere in the component's box: a y below the last line resolves to end-of-document, which
     * is a position no reader can drag to.
     */
    fun dragAcross(component: JTextComponent, fromX: Int = 10, toX: Int = 70) {
        val lineY = firstLineY(component)
        val start = System.currentTimeMillis()

        post(component, MouseEvent.MOUSE_PRESSED, fromX, lineY, start, MouseEvent.BUTTON1)
        for (step in 1..6) {
            val x = fromX + (toX - fromX) * step / 6
            post(component, MouseEvent.MOUSE_DRAGGED, x, lineY, start + step * 50L, NO_BUTTON)
        }
        post(component, MouseEvent.MOUSE_RELEASED, toX, lineY, start + 500, NO_BUTTON)
        settle()
    }

    /** Clears any selection, the way a click somewhere else would. */
    fun clearSelection(component: JTextComponent) {
        onEdt { component.select(0, 0) }
        settle()
    }

    fun selectedText(component: JTextComponent): String? = onEdtResult { component.selectedText }

    fun selectAll(component: JTextComponent) {
        onEdt { component.selectAll() }
        settle()
    }

    private fun firstLineY(component: JTextComponent): Int = onEdtResult {
        // Hit testing needs a laid-out component: without a width the view has no rows to map.
        if (component.width <= 0) component.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        val bounds = component.modelToView2D(0).bounds
        bounds.y + maxOf(2, component.getFontMetrics(component.font).height / 2)
    }

    private fun post(
        component: JTextComponent,
        id: Int,
        x: Int,
        y: Int,
        start: Long,
        button: Int,
    ) {
        val modifiers = if (id == MouseEvent.MOUSE_RELEASED) 0 else InputEvent.BUTTON1_DOWN_MASK
        onEdt {
            component.dispatchEvent(
                MouseEvent(component, id, start, modifiers, x, y, 1, false, button)
            )
        }
    }

    /** Lets pending layout and selection work settle before a value is read. */
    private fun settle() {
        onEdt { }
        onEdt { }
    }

    private const val DEFAULT_WIDTH = 500
    private const val DEFAULT_HEIGHT = 60

    private fun <T> onEdtResult(block: () -> T): T {
        var result: Any? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}

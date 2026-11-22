package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationPopoverPlacementTest {

    private val windowHeight = 700
    private val bellSize = 24
    private val popoverWidth = 368
    private val popoverHeight = 248

    private fun offset(anchorTop: Int, ltr: Boolean = true, windowHeight: Int = this.windowHeight) = popoverOffset(
        anchorWidth = bellSize,
        anchorHeight = bellSize,
        anchorTopInWindow = anchorTop,
        windowHeight = windowHeight,
        popoverWidth = popoverWidth,
        popoverHeight = popoverHeight,
        isLeftToRight = ltr,
    )

    @Test
    fun `a bell in the status bar opens upward`() {
        assertEquals(-popoverHeight, offset(anchorTop = windowHeight - bellSize - 4).y)
    }

    @Test
    fun `a bell in the history row opens downward`() {
        assertEquals(bellSize, offset(anchorTop = 8).y)
    }

    @Test
    fun `a bell in the input strip opens upward when the strip is in the lower half`() {
        assertEquals(-popoverHeight, offset(anchorTop = 500).y)
    }

    @Test
    fun `the midline decides and the lower half wins a tie`() {
        // The centre of the bell against half the window height.
        val topAtMidline = windowHeight / 2 - bellSize / 2
        assertEquals(-popoverHeight, offset(anchorTop = topAtMidline).y, "centre exactly at the midline")
        assertEquals(bellSize, offset(anchorTop = topAtMidline - 1).y, "centre just above the midline")
    }

    @Test
    fun `an anchor with no window to measure opens upward as it always did`() {
        assertEquals(-popoverHeight, offset(anchorTop = Int.MAX_VALUE, windowHeight = 0).y)
        assertEquals(-popoverHeight, offset(anchorTop = 0, windowHeight = 0).y)
    }

    @Test
    fun `the trailing edge lines up with the anchor in both directions`() {
        // Left to right: the bell is at the right end, so the popover grows leftwards.
        assertEquals(bellSize - popoverWidth, offset(anchorTop = 8, ltr = true).x)
        // Right to left: the bell is at the left end, so the popover grows rightwards.
        assertEquals(0, offset(anchorTop = 8, ltr = false).x)
    }

    @Test
    fun `direction and alignment are independent`() {
        assertEquals(PopoverOffset(0, bellSize), offset(anchorTop = 8, ltr = false))
        assertEquals(PopoverOffset(0, -popoverHeight), offset(anchorTop = 600, ltr = false))
        assertEquals(PopoverOffset(bellSize - popoverWidth, bellSize), offset(anchorTop = 8, ltr = true))
    }
}

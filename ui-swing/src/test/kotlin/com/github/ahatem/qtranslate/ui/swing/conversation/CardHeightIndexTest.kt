package com.github.ahatem.qtranslate.ui.swing.conversation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardHeightIndexTest {

    @Test
    fun `prefix sums, topOf, totalHeight, and binary-searched cardAt are exact`() {
        val index = CardHeightIndex()
        val heightsById = mapOf(
            "c1" to 48,
            "c2" to 72,
            "c3" to 60,
            "c4" to 96
        )
        val specs = heightsById.keys.map { CardHeightIndex.CardSpec(it, contentVersion = 1) }

        val measured = index.sync(specs, newWidth = 500) { heightsById.getValue(it) }
        assertEquals(4, measured)
        assertEquals(4, index.size)
        assertEquals(276, index.totalHeight)

        assertEquals(0, index.topOf("c1"))
        assertEquals(48, index.topOf("c2"))
        assertEquals(120, index.topOf("c3"))
        assertEquals(180, index.topOf("c4"))
        assertNull(index.topOf("missing"))

        // Boundary checks for cardAt(y)
        assertEquals(-1, index.cardAt(-1))
        assertEquals(0, index.cardAt(0))
        assertEquals(0, index.cardAt(47))
        assertEquals(1, index.cardAt(48))
        assertEquals(1, index.cardAt(119))
        assertEquals(2, index.cardAt(120))
        assertEquals(2, index.cardAt(179))
        assertEquals(3, index.cardAt(180))
        assertEquals(3, index.cardAt(275))
        assertEquals(-1, index.cardAt(276))
    }

    @Test
    fun `inserting above and below reuses cached heights and invalidates only changed card or wholesale on width change`() {
        val index = CardHeightIndex()
        val heightTable = mutableMapOf(
            "c1" to 50,
            "c2" to 60,
            "c3" to 70
        )

        val initial = listOf("c1", "c2", "c3").map { CardHeightIndex.CardSpec(it, contentVersion = "v1") }
        assertEquals(3, index.sync(initial, newWidth = 600) { heightTable.getValue(it) })

        // Re-sync with identical specs measures 0 cards
        assertEquals(0, index.sync(initial, newWidth = 600) { heightTable.getValue(it) })

        // Insert "c0" above and "c4" below: only the 2 new cards are measured
        heightTable["c0"] = 40
        heightTable["c4"] = 80
        val withInserts = listOf("c0", "c1", "c2", "c3", "c4").map { CardHeightIndex.CardSpec(it, contentVersion = "v1") }
        assertEquals(2, index.sync(withInserts, newWidth = 600) { heightTable.getValue(it) })
        assertEquals(300, index.totalHeight)
        assertEquals(0, index.topOf("c0"))
        assertEquals(40, index.topOf("c1"))
        assertEquals(220, index.topOf("c4"))

        // Content version change on "c2" invalidates only "c2"
        heightTable["c2"] = 95
        val updatedC2 = withInserts.map { if (it.segmentId == "c2") it.copy(contentVersion = "v2") else it }
        assertEquals(1, index.sync(updatedC2, newWidth = 600) { heightTable.getValue(it) })
        assertEquals(335, index.totalHeight)
        assertEquals(90, index.topOf("c2"))
        assertEquals(185, index.topOf("c3"))

        // Explicit invalidate(segmentId) re-measures only that card
        heightTable["c1"] = 55
        index.invalidate("c1")
        assertEquals(1, index.sync(updatedC2, newWidth = 600) { heightTable.getValue(it) })
        assertEquals(340, index.totalHeight)

        // Width change invalidates all 5 cards wholesale
        assertEquals(5, index.sync(updatedC2, newWidth = 480) { heightTable.getValue(it) })
    }

    @Test
    fun `inserting N cards of known heights above the anchor shifts target scroll by the exact sum and preserves on-screen Y`() {
        val index = CardHeightIndex()
        val heights = mutableMapOf<String, Int>()
        for (i in 1..20) {
            heights["card-$i"] = 50 + (i % 3) * 10
        }
        val initialSpecs = (1..20).map { CardHeightIndex.CardSpec("card-$it", contentVersion = 1) }
        index.sync(initialSpecs, newWidth = 600) { heights.getValue(it) }

        val anchorId = "card-5"
        val initialTop = index.topOf(anchorId)!!
        val initialScrollY = initialTop - 110
        val viewportOriginY = 96
        val anchor = index.captureAnchor(anchorId, viewPositionY = initialScrollY, viewportOriginY = viewportOriginY)!!
        val initialScreenY = initialTop - initialScrollY + viewportOriginY
        assertEquals(anchor.screenY, initialScreenY)

        // Insert N = 50 cards of known heights above the anchored card
        val insertedHeights = (1..50).map { i -> 44 + (i % 5) * 8 }
        val expectedShiftSum = insertedHeights.sum()
        val insertedSpecs = (1..50).map { i ->
            val id = "new-$i"
            heights[id] = insertedHeights[i - 1]
            CardHeightIndex.CardSpec(id, contentVersion = 1)
        }

        index.sync(insertedSpecs + initialSpecs, newWidth = 600) { heights.getValue(it) }

        val newTop = index.topOf(anchorId)!!
        assertEquals(initialTop + expectedShiftSum, newTop, "Card top in index must shift by the exact sum of inserted heights")

        val newScrollY = index.computeAnchoredScrollY(anchor, viewportHeight = 400, newViewportOriginY = viewportOriginY)!!
        assertEquals(
            initialScrollY + expectedShiftSum,
            newScrollY,
            "Anchored scroll Y must shift by the exact sum of inserted heights ($expectedShiftSum px)"
        )

        val newScreenY = newTop - newScrollY + viewportOriginY
        assertEquals(initialScreenY, newScreenY, "On-screen Y of anchored card must remain identical to the pixel")
    }

    @Test
    fun `visibleRange returns only cards intersecting clip plus overscan`() {
        val index = CardHeightIndex()
        val specs = (0 until 2000).map { CardHeightIndex.CardSpec("seg-$it", contentVersion = 1) }
        index.sync(specs, newWidth = 600) { 60 }

        // Viewport clip at y = 3000..3400 (cards 50..56), overscan = 2 -> 48..58
        val range = index.visibleRange(clipTop = 3000, clipBottom = 3400, overscan = 2)
        assertEquals(48..58, range)
        assertTrue(range.count() <= 11)
    }
}

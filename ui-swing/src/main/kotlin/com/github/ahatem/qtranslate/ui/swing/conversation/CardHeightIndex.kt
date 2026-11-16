package com.github.ahatem.qtranslate.ui.swing.conversation

/**
 * Pure height and prefix-sum index for the virtualised transcript card list (no Swing dependencies).
 *
 * Caches measured heights keyed by `(segmentId, width, contentVersion)`, maintains prefix sums for
 * $O(1)$ `topOf(id)` / `totalHeight` and $O(\log N)$ `cardAt(y)` / `visibleRange(...)` queries,
 * invalidates individual cards when their content changes, and invalidates all cached heights when
 * the layout width changes.
 */
class CardHeightIndex(
    val topPadding: Int = 0,
    val bottomPadding: Int = 0,
    val cardGap: Int = 0
) {
    data class CardSpec(
        val segmentId: String,
        val contentVersion: Any
    )

    data class IndexAnchor(
        val segmentId: String,
        val offsetFromViewportTop: Int,
        val viewportOriginY: Int = 0,
        val pointerOffsetInCard: Int? = null
    ) {
        val screenY: Int get() = viewportOriginY + offsetFromViewportTop
    }

    private data class CachedHeight(
        val width: Int,
        val contentVersion: Any,
        val height: Int
    )

    var width: Int = 0
        private set

    private val cachedHeights = HashMap<String, CachedHeight>()
    private var orderedIds: List<String> = emptyList()
    private val idToIndex = HashMap<String, Int>()
    private var heights: IntArray = IntArray(0)
    private var prefixTops: IntArray = IntArray(1) { topPadding }
    private var totalHeightValue: Int = 0

    val size: Int get() = orderedIds.size

    val totalHeight: Int get() = totalHeightValue

    val ids: List<String> get() = orderedIds

    /**
     * Updates the index for [cards] at [newWidth], measuring only cards whose height is not already
     * cached for `(segmentId, newWidth, contentVersion)`.
     *
     * Returns the number of cards that had to be measured via [measureHeight].
     */
    fun sync(
        cards: List<CardSpec>,
        newWidth: Int,
        measureHeight: (segmentId: String) -> Int
    ): Int {
        if (newWidth != width) {
            width = newWidth
            cachedHeights.clear()
        }

        val activeIds = HashSet<String>(cards.size * 2)
        val newOrderedIds = ArrayList<String>(cards.size)
        val newHeights = IntArray(cards.size)
        idToIndex.clear()

        var measuredCount = 0
        for (i in cards.indices) {
            val spec = cards[i]
            activeIds.add(spec.segmentId)
            newOrderedIds.add(spec.segmentId)
            idToIndex[spec.segmentId] = i

            val cached = cachedHeights[spec.segmentId]
            val h = if (cached != null && cached.width == newWidth && cached.contentVersion == spec.contentVersion) {
                cached.height
            } else {
                measuredCount++
                val measured = measureHeight(spec.segmentId).coerceAtLeast(1)
                cachedHeights[spec.segmentId] = CachedHeight(newWidth, spec.contentVersion, measured)
                measured
            }
            newHeights[i] = h
        }

        if (cachedHeights.size > activeIds.size) {
            val iter = cachedHeights.keys.iterator()
            while (iter.hasNext()) {
                if (iter.next() !in activeIds) {
                    iter.remove()
                }
            }
        }

        orderedIds = newOrderedIds
        heights = newHeights
        rebuildPrefixSums()
        return measuredCount
    }

    /** Invalidates the cached height for [segmentId] so the next [sync] or [recalculate] re-measures it. */
    fun invalidate(segmentId: String) {
        cachedHeights.remove(segmentId)
    }

    /**
     * Updates a single existing card at [index] in place without rebuilding the full ID list or hash map.
     */
    fun updateCardAt(index: Int, segmentId: String, contentVersion: Any, newHeight: Int) {
        if (index !in orderedIds.indices || orderedIds[index] != segmentId) return
        val clamped = newHeight.coerceAtLeast(1)
        cachedHeights[segmentId] = CachedHeight(width, contentVersion, clamped)
        val delta = clamped - heights[index]
        if (delta == 0) return
        heights[index] = clamped
        for (i in (index + 1)..orderedIds.size) {
            prefixTops[i] += delta
        }
        totalHeightValue += delta
    }

    /** Invalidates all cached heights (for example when theme font or layout mode changes). */
    fun invalidateAll() {
        cachedHeights.clear()
    }

    /** Updates the width and clears all width-dependent cached heights when [newWidth] differs. */
    fun setWidth(newWidth: Int) {
        if (newWidth != width) {
            width = newWidth
            cachedHeights.clear()
        }
    }

    fun indexOf(segmentId: String): Int = idToIndex[segmentId] ?: -1

    fun idAt(index: Int): String? = orderedIds.getOrNull(index)

    fun topOfIndex(index: Int): Int {
        if (index !in orderedIds.indices) return -1
        return prefixTops[index]
    }

    fun heightOfIndex(index: Int): Int {
        if (index !in orderedIds.indices) return 0
        return heights[index]
    }

    fun bottomOfIndex(index: Int): Int {
        if (index !in orderedIds.indices) return -1
        return prefixTops[index] + heights[index]
    }

    fun topOf(segmentId: String): Int? {
        val idx = idToIndex[segmentId] ?: return null
        return prefixTops[idx]
    }

    fun heightOf(segmentId: String): Int? {
        val idx = idToIndex[segmentId] ?: return null
        return heights[idx]
    }

    fun bottomOf(segmentId: String): Int? {
        val idx = idToIndex[segmentId] ?: return null
        return prefixTops[idx] + heights[idx]
    }

    /**
     * Binary-searches for the card at vertical coordinate [y].
     * When [y] falls inside the inter-card gap between card $i$ and card $i+1$, returns card $i$
     * if [clampToNearest] is true, or `-1` when strictly outside any card.
     */
    fun cardAt(y: Int, clampToNearest: Boolean = true): Int {
        val count = orderedIds.size
        if (count == 0) return -1
        if (y < prefixTops[0]) return if (clampToNearest && y >= 0) 0 else -1
        if (y >= totalHeightValue) return -1

        var low = 0
        var high = count - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (prefixTops[mid] <= y) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return -1
        val bottom = prefixTops[candidate] + heights[candidate]
        if (y < bottom) return candidate
        return if (clampToNearest) candidate else -1
    }

    fun cardIdAt(y: Int, clampToNearest: Boolean = true): String? {
        val idx = cardAt(y, clampToNearest)
        return if (idx >= 0) orderedIds[idx] else null
    }

    /**
     * Returns the index range of cards intersecting `[clipTop, clipBottom]` expanded by [overscan]
     * cards above and below, or [IntRange.EMPTY] if the list is empty.
     */
    fun visibleRange(clipTop: Int, clipBottom: Int, overscan: Int = 2): IntRange {
        val count = orderedIds.size
        if (count == 0 || clipBottom < 0) return IntRange.EMPTY
        val clampedTop = clipTop.coerceIn(0, (totalHeightValue - 1).coerceAtLeast(0))
        val clampedBottom = clipBottom.coerceIn(clampedTop, (totalHeightValue - 1).coerceAtLeast(0))

        val first = cardAt(clampedTop, clampToNearest = true).let { if (it < 0) 0 else it }
        val last = cardAt(clampedBottom, clampToNearest = true).let { if (it < 0) count - 1 else it }
        val start = (first - overscan).coerceIn(0, count - 1)
        val end = (last + overscan).coerceIn(start, count - 1)
        return start..end
    }

    /** Captures the scroll anchor for [segmentId] at the given [viewPositionY] and [viewportOriginY]. */
    fun captureAnchor(
        segmentId: String,
        viewPositionY: Int,
        viewportOriginY: Int = 0,
        pointerOffsetInCard: Int? = null
    ): IndexAnchor? {
        val top = topOf(segmentId) ?: return null
        return IndexAnchor(
            segmentId = segmentId,
            offsetFromViewportTop = top - viewPositionY,
            viewportOriginY = viewportOriginY,
            pointerOffsetInCard = pointerOffsetInCard
        )
    }

    /**
     * Computes the new `viewPosition.y` that keeps [anchor]'s card at the exact same on-screen Y
     * (`anchor.screenY`) when the viewport is at [newViewportOriginY] with height [viewportHeight].
     */
    fun computeAnchoredScrollY(
        anchor: IndexAnchor,
        viewportHeight: Int,
        newViewportOriginY: Int = anchor.viewportOriginY
    ): Int? {
        val newTop = topOf(anchor.segmentId) ?: return null
        val desiredOffsetFromViewportTop = anchor.screenY - newViewportOriginY
        val maxScroll = (totalHeightValue - viewportHeight.coerceAtLeast(0)).coerceAtLeast(0)
        return (newTop - desiredOffsetFromViewportTop).coerceIn(0, maxScroll)
    }

    private fun rebuildPrefixSums() {
        val count = orderedIds.size
        if (count == 0) {
            prefixTops = IntArray(1) { topPadding }
            totalHeightValue = 0
            return
        }
        val tops = IntArray(count + 1)
        var y = topPadding
        for (i in 0 until count) {
            tops[i] = y
            y += heights[i]
            if (i < count - 1) {
                y += cardGap
            }
        }
        tops[count] = y
        prefixTops = tops
        totalHeightValue = y + bottomPadding
    }
}

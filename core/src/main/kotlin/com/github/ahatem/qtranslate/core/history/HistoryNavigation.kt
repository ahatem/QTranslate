package com.github.ahatem.qtranslate.core.history

internal data class HistorySelection(val index: Int, val snapshot: HistorySnapshot?)

internal fun previousHistory(history: List<HistorySnapshot>, index: Int): HistorySelection? =
    if (index > 0) HistorySelection(index - 1, history[index - 1]) else null

internal fun nextHistory(history: List<HistorySnapshot>, index: Int): HistorySelection? =
    if (index < history.size) {
        val next = index + 1
        HistorySelection(next, history.getOrNull(next))
    } else null

internal fun restoredHistoryIndex(history: List<HistorySnapshot>, index: Int, snapshot: HistorySnapshot): Int =
    history.indexOf(snapshot).let { if (it >= 0) it + 1 else index }

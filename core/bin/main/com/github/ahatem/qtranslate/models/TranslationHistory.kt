package com.github.ahatem.qtranslate.models

data class TranslationHistorySnapshot(
  val selectedTranslatorIndex: Int,
  val inputLanguage: String,
  val outputLanguage: String,
  val originalText: String,
  val translatedText: String,
  val backwardTranslationText: String
)

object TranslationHistory {
  private val _snapshots = mutableListOf<Pair<String, MutableList<TranslationHistorySnapshot>>>()
  private var currentListIndex = -1
  private var currentSnapshotIndex = -1

  val snapshots get() = _snapshots.toList()

  fun saveSnapshot(state: TranslationHistorySnapshot) {
    if (_snapshots.isEmpty()) {
      _snapshots.add(state.originalText.trim() to mutableListOf(state))
    } else {
      val last = _snapshots.last()
      if (last.first == state.originalText.trim()) {
        if (!last.second.any { it.selectedTranslatorIndex == state.selectedTranslatorIndex }) {
          last.second.add(state)
        }
      } else {
        _snapshots.add(state.originalText.trim() to mutableListOf(state))
      }
    }
    currentListIndex = _snapshots.lastIndex
    currentSnapshotIndex = _snapshots.last().second.lastIndex
  }


  fun undo(): TranslationHistorySnapshot {
    currentSnapshotIndex--
    if (currentSnapshotIndex < 0) {
      if (currentListIndex - 1 < 0) {
        currentListIndex = 0
        currentSnapshotIndex = 0
      } else {
        currentListIndex--
        currentSnapshotIndex = _snapshots[currentListIndex].second.lastIndex
      }
    }
    return _snapshots[currentListIndex].second[currentSnapshotIndex]
  }

  fun redo(): TranslationHistorySnapshot {
    currentSnapshotIndex++

    if (currentSnapshotIndex > _snapshots[currentListIndex].second.lastIndex) {
      if (currentListIndex + 1 > _snapshots.lastIndex) {
        currentListIndex = _snapshots.lastIndex
        currentSnapshotIndex = _snapshots[currentListIndex].second.lastIndex
      } else {
        currentListIndex++
        currentSnapshotIndex = 0
      }
    }

    return _snapshots[currentListIndex].second[currentSnapshotIndex]
  }

  fun canUndo(): Boolean {
    return currentListIndex > 0 || (currentListIndex == 0 && currentSnapshotIndex > 0)
  }

  fun canRedo(): Boolean {
    if (_snapshots.isEmpty()) return false
    return currentListIndex < _snapshots.lastIndex || (currentListIndex == _snapshots.lastIndex && currentSnapshotIndex < _snapshots[currentListIndex].second.lastIndex)
  }

  fun clear() {
    _snapshots.clear()
    currentListIndex = -1
    currentSnapshotIndex = -1
  }

}

package com.pnix.qtranslate.models


data class TranslationHistorySnapshot(
  val selectedTranslatorIndex: Int,
  val inputLanguage: String,
  val outputLanguage: String,
  val originalText: String,
  val translatedText: String,
  val backwardTranslationText: String
)

object TranslationHistory {
  private val snapshots = mutableListOf<TranslationHistorySnapshot>()
  private var currentIndex = -1

  fun saveSnapshot(state: TranslationHistorySnapshot) {
    if (snapshots.lastOrNull() != state) {
      snapshots.add(state)
      currentIndex = snapshots.lastIndex
    }
  }

  fun undo(): TranslationHistorySnapshot? {
    if (canUndo()) {
      currentIndex--
      return snapshots[currentIndex]
    }
    return null
  }

  fun redo(): TranslationHistorySnapshot? {
    if (canRedo()) {
      currentIndex++
      return snapshots[currentIndex]
    }
    return null
  }

  fun canUndo(): Boolean {
    return currentIndex > 0
  }

  fun canRedo(): Boolean {
    return currentIndex < snapshots.lastIndex
  }
}

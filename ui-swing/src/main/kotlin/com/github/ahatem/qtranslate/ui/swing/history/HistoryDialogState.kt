package com.github.ahatem.qtranslate.ui.swing.history

import com.github.ahatem.qtranslate.core.history.HistorySnapshot

data class HistoryEntryState(
    val date: String,
    val sourceText: String,
    val translatedText: String,
    val languages: String,
    val service: String,
    val snapshot: HistorySnapshot
)

data class HistoryDialogState(
    val title: String,
    val columnDate: String,
    val columnSource: String,
    val columnTranslation: String,
    val columnLanguages: String,
    val columnService: String,
    val emptyMessage: String,
    val clearAllLabel: String,
    val closeLabel: String,
    val restoreTooltip: String,
    val entries: List<HistoryEntryState>,
    val onEntrySelected: (HistorySnapshot) -> Unit,
    val onClearAll: () -> Unit
)

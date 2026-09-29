package com.github.ahatem.qtranslate.ui.swing.history

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun buildHistoryDialogState(
    mainState: MainState,
    localizer: LocalizationManager,
    onEntrySelected: (HistorySnapshot) -> Unit,
    onClearAll: () -> Unit
): HistoryDialogState {
    val fmt = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val services = mainState.availableServices
    val entries = mainState.history.reversed().map { snap ->
        val serviceName = services.find { it.id == snap.translatorId }?.name ?: snap.translatorId

        val sourceLanguage = LanguageCode(snap.sourceLanguage).getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect"))
        val targetLanguage = LanguageCode(snap.targetLanguage).getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect"))

        HistoryEntryState(
            date = fmt.format(Date(snap.timestamp)),
            sourceText = snap.inputText.take(80).let { if (snap.inputText.length > 80) "$it…" else it },
            translatedText = snap.translatedText.take(80).let { if (snap.translatedText.length > 80) "$it…" else it },
            languages = "$sourceLanguage → $targetLanguage",
            service = serviceName,
            snapshot = snap
        )
    }
    return HistoryDialogState(
        title = localizer.getString("history_dialog.title"),
        columnDate = localizer.getString("history_dialog.column_date"),
        columnSource = localizer.getString("history_dialog.column_source"),
        columnTranslation = localizer.getString("history_dialog.column_translation"),
        columnLanguages = localizer.getString("history_dialog.column_languages"),
        columnService = localizer.getString("history_dialog.column_service"),
        emptyMessage = localizer.getString("history_dialog.empty_message"),
        clearAllLabel = localizer.getString("common.clear_all"),
        closeLabel = localizer.getString("common.close"),
        restoreTooltip = localizer.getString("history_dialog.restore_tooltip"),
        entries = entries,
        onEntrySelected = onEntrySelected,
        onClearAll = onClearAll
    )
}

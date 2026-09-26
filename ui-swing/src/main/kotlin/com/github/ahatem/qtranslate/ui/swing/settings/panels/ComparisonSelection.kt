package com.github.ahatem.qtranslate.ui.swing.settings.panels

internal data class ComparisonServiceOption(
    val id: String,
    val name: String,
    val available: Boolean
)

internal fun comparisonChooserOptions(
    available: List<ComparisonServiceOption>,
    selectedIds: List<String>,
    primaryId: String?,
    unavailableName: String
): List<ComparisonServiceOption> {
    val availableIds = available.map { it.id }.toSet()
    val unavailable = selectedIds
        .asSequence()
        .filter { it !in availableIds && it != primaryId }
        .map { ComparisonServiceOption(it, unavailableName, available = false) }
        .toList()
    return unavailable + available.filter { it.id != primaryId }
}

internal fun toggleComparisonId(selectedIds: List<String>, id: String, selected: Boolean): List<String> =
    if (selected) if (id in selectedIds) selectedIds else selectedIds + id
    else selectedIds.filterNot { it == id }

internal fun comparisonPopupX(buttonWidth: Int, popupWidth: Int, isLeftToRight: Boolean): Int =
    if (isLeftToRight) 0 else buttonWidth - popupWidth

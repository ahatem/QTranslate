package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFallbackFont
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFont

internal fun buildQuickTranslateDialogState(
    mainState: MainState,
    config: Configuration,
    localizer: LocalizationManager
): QuickTranslateDialogState {
    val displaySourceLanguage = mainState.detectedSourceLanguage ?: mainState.sourceLanguage

    val activePreset = config.getActivePreset()
    val selectedTranslatorId = activePreset?.selectedServices?.get(ServiceRole.TRANSLATOR)
    val selectedTranslator = mainState.availableServices.find { it.id == selectedTranslatorId }

    return QuickTranslateDialogState(
        isVisible = mainState.isQuickTranslateDialogVisible,
        isLoading = mainState.isLoading,
        translatedText = mainState.translatedText,
        isPinned = mainState.isQuickTranslateDialogPinned,
        triggerCount = mainState.quickTranslateTriggerCount,
        isTtsPlaying = mainState.isTtsPlaying,
        definition = mainState.inlineDefinition,

        sourceLanguage = displaySourceLanguage,
        targetLanguage = mainState.targetLanguage,
        availableLanguages = mainState.availableLanguages,
        detectedSourceLanguage = mainState.detectedSourceLanguage,

        translatorSelectorState = QuickTranslateSelectorState(
            availableTranslators = mainState.getAvailableServicesFor(ServiceRole.TRANSLATOR),
            selectedTranslatorId = selectedTranslator?.id
        ),
        actionsState = QuickTranslateActionsState(
            canCopy = mainState.translatedText.isNotBlank(),
            canListen = mainState.translatedText.isNotBlank()
        ),
        config = DialogConfig(
            font = config.scaledEditorFont,
            fallbackFont = config.scaledEditorFallbackFont,
            autoSizeEnabled = config.isPopupAutoSizeEnabled,
            autoPositionEnabled = config.isPopupAutoPositionEnabled,
            transparencyPercentage = config.popupTransparencyPercentage,
            idleTimeoutSeconds = config.popupIdleTimeoutSeconds,
            closeOnClickOutside = config.closePopupsOnClickOutside,
            lastKnownSize = config.popupLastKnownSize,
            lastKnownPosition = config.popupLastKnownPosition
        ),
        strings = DialogStrings(
            copyTooltip = localizer.getString("common.copy"),
            closeTooltip = localizer.getString("common.close"),
            listenTooltip = localizer.getString("common.listen"),
            stopListeningTooltip = localizer.getString("common.stop"),
            pinTooltip = localizer.getString("common.pin"),
            unpinTooltip = localizer.getString("common.unpin"),
            swapTooltip = localizer.getString("main_window_language_bar.swap_languages_tooltip"),
            loadingText = localizer.getString("common.loading")
        ),
        comparisonResults = mainState.comparisonResults,
        comparisonLoadingText = localizer.getString("main_window.comparison_loading"),
        comparisonUnavailableText = localizer.getString("main_window.comparison_unavailable"),
        comparisonFailureText = localizer.getString("main_window.comparison_failure"),
        comparisonCopyLabel = localizer.getString("main_window.comparison_copy"),
        comparisonDetailsLabel = localizer.getString("main_window.comparison_details"),
        primaryProviderName = selectedTranslator?.name ?: localizer.getString("main_window.no_translator"),
        primaryBadge = localizer.getString("main_window.comparison_primary"),
        primaryProviderInfo = selectedTranslator,
        comparisonProviderInfos = mainState.availableServices.associateBy { it.id },
        // Canonical primary only with fewer than two usable translators;
        // comparison results appear only once Comparison is eligible.
        comparisonsEnabled = config.isComparisonEligible(mainState.availableTranslatorIds)
    )
}

package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.usecase.ComparisonPolicy
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds

internal fun mainTranslationComparisonPolicy(layoutPresetId: String): ComparisonPolicy =
    if (layoutPresetId == LayoutPresetIds.COMPARISON) ComparisonPolicy.ENABLED
    else ComparisonPolicy.DISABLED

/**
 * Quick Translate compares whenever at least two translators are effective,
 * independent of the saved layout; with fewer it shows the canonical primary only.
 */
internal fun quickTranslationComparisonPolicy(comparisonEligible: Boolean): ComparisonPolicy =
    if (comparisonEligible) ComparisonPolicy.ENABLED
    else ComparisonPolicy.DISABLED

/**
 * Edge trigger for the "Comparison needs two translators" notice: announce
 * when a fallback starts, stay quiet while it continues, re-arm once it ends.
 */
internal fun shouldAnnounceComparisonFallback(needsFallback: Boolean, alreadyAnnounced: Boolean): Boolean =
    needsFallback && !alreadyAnnounced

/** True while the primary translation or any comparison is still running. */
internal fun MainState.hasPrimaryOrComparisonInFlight(): Boolean =
    isLoading || comparisonResults.any { it.status == ComparisonStatus.LOADING }

/** As [hasPrimaryOrComparisonInFlight], plus extra output, which is published after the primary. */
internal fun MainState.hasAnyTranslationWorkInFlight(): Boolean =
    hasPrimaryOrComparisonInFlight() || isExtraOutputLoading

/** What closing the Quick popup must stop: running work, and any comparison rows it left unfinished. */
internal data class QuickCloseActions(val cancelWork: Boolean, val clearComparisons: Boolean)

internal fun MainState.quickCloseActions(): QuickCloseActions = QuickCloseActions(
    cancelWork = hasAnyTranslationWorkInFlight(),
    clearComparisons = hasPrimaryOrComparisonInFlight()
)

/** Closing the Quick popup is presentation only: it hides the popup and drops its pin. */
internal fun MainState.afterQuickClose(): MainState = copy(
    isQuickTranslateDialogVisible = false,
    isQuickTranslateDialogPinned = false,
    isLoading = false,
    isExtraOutputLoading = false
)

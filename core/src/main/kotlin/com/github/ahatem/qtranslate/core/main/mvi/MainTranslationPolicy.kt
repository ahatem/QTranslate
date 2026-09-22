package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.main.domain.usecase.ComparisonPolicy
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds

internal fun mainTranslationComparisonPolicy(layoutPresetId: String): ComparisonPolicy =
    if (layoutPresetId == LayoutPresetIds.COMPARISON) ComparisonPolicy.ENABLED
    else ComparisonPolicy.DISABLED

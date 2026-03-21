package com.github.ahatem.qtranslate.core.main.domain.model

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * Snapshot of available services and the languages supported by the
 * currently selected translator.
 *
 * Service *selections* (which service is active per type) live in
 * [com.github.ahatem.qtranslate.core.settings.data.Configuration.servicePresets],
 * not here. This only describes what is *available*.
 *
 * @property availableServices All services currently loaded and not disabled,
 *   mapped to [ServiceInfo] for UI display.
 * @property availableLanguages Languages supported by the currently active translator.
 *   Empty if no translator is active or its language list has not loaded yet.
 */
data class ServiceSelectionState(
    val availableServices: List<ServiceInfo>,
    val availableLanguages: List<LanguageCode>
)
package com.github.ahatem.qtranslate.core.main.domain.model

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceOption
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType

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
    val availableLanguages: List<LanguageCode>,
    /**
     * The options declared by whichever service is currently active for each capability.
     *
     * The host no longer owns the vocabulary for things like summary length or rewrite style, so
     * the pickers that offer them are built from this rather than from a fixed enum. A service
     * offering something the application has never heard of appears here and is rendered like any
     * other choice.
     */
    val serviceOptions: Map<ServiceType, List<ServiceOption>> = emptyMap()
)
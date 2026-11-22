package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle

data class TranslatorSelectorState(
    val availableTranslators: List<ServiceInfo>,
    val selectedTranslatorId: String?,
    val isLoading: Boolean,
    val availableServices: List<ServiceInfo> = availableTranslators,
    val selectedServices: Map<ServiceRole, String?> = selectedTranslatorId?.let {
        mapOf(ServiceRole.TRANSLATOR to it)
    }.orEmpty(),
    val style: ServiceSelectorStyle = ServiceSelectorStyle.CLASSIC,
    val appearance: ServiceSelectorAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT,
    val strings: TranslatorSelectorStrings = TranslatorSelectorStrings.DEFAULT
) : UiState

/**
 * The words the service selector draws.
 *
 * Carried with each render rather than handed to the widget once, because the interface language
 * can change while the window is open and the selector is not rebuilt for it.
 *
 * [DEFAULT] is English, for the callers that have no localization of their own: the widget's state
 * before its first render, and tests that are not about wording.
 */
data class TranslatorSelectorStrings(
    val moreButton: String,
    val allServicesTooltip: String,
    val configureActiveTooltip: String,
    val translatorRole: String,
    val dictionaryRole: String,
    val voiceRole: String,
    /** `%1$s` is the role's name. */
    val selectServiceTooltip: String,
    /** `%1$s` is the role's name. */
    val configureServiceTooltip: String,
    /** Names what the provider chip does, for the hover tooltip. */
    val providerChipTooltip: String = "Translation service",
    /** What the provider chip says when no translator is selected. */
    val noTranslator: String = "No Translator"
) {
    companion object {
        val DEFAULT = TranslatorSelectorStrings(
            moreButton = "More",
            allServicesTooltip = "All services",
            configureActiveTooltip = "Configure active translation service",
            translatorRole = "Translate",
            dictionaryRole = "Dictionary",
            voiceRole = "Voice",
            selectServiceTooltip = "Select %1\$s service",
            configureServiceTooltip = "Configure selected %1\$s service",
            providerChipTooltip = "Translation service",
            noTranslator = "No Translator"
        )
    }
}

/**
 * The selector's words in the interface language.
 *
 * One definition for the main window and the Quick Translate popup, so the two cannot drift into
 * wording the rest of the application no longer uses.
 */
fun LocalizationManager.serviceSelectorStrings() = TranslatorSelectorStrings(
    moreButton = getString("main_window_service_selector.more"),
    allServicesTooltip = getString("main_window_service_selector.all_services_tooltip"),
    configureActiveTooltip = getString("main_window_service_selector.configure_active_tooltip"),
    translatorRole = getString("main_window_service_selector.role_translator"),
    dictionaryRole = getString("main_window_service_selector.role_dictionary"),
    voiceRole = getString("main_window_service_selector.role_voice"),
    selectServiceTooltip = getString("main_window_service_selector.select_service_tooltip"),
    configureServiceTooltip = getString("main_window_service_selector.configure_service_tooltip"),
    providerChipTooltip = getString("main_window_service_selector.provider_chip_tooltip"),
    noTranslator = getString("main_window.no_translator")
)

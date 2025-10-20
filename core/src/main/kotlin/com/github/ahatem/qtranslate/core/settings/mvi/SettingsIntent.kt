package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.arch.UiIntent

/** User actions within the settings UI. */
sealed interface SettingsIntent : UiIntent {
    /** User modified a setting, not yet saved. */
    data class SettingChanged(val newConfiguration: Configuration) : SettingsIntent

    /** User clicked the "Save" or "Apply" button. */
    data object SaveChanges : SettingsIntent

    /** User saved settings for a specific plugin. */
    data class SavePluginSettings(val pluginId: String, val settings: Map<String, String>) : SettingsIntent

    /** Change the globally active service preset. */
    data class SetActivePreset(val presetId: String) : SettingsIntent

    /** Update a service selection in the active preset. */
    data class UpdateServiceInActivePreset(val type: ServiceType, val serviceId: String?) : SettingsIntent
}
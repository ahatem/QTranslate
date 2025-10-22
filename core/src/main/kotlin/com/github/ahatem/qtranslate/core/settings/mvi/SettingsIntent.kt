package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.arch.UiIntent

/**
 * User actions for settings management.
 *
 * Intents are organized into two categories:
 *
 * 1. Draft Mode (for settings dialog):
 *    - UpdateDraft: Updates working configuration without persisting
 *    - SaveChanges: Persists working configuration
 *    - CancelChanges: Reverts working to original
 *    - ResetToDefaults: Sets working to default configuration
 *
 * 2. Quick Actions (auto-save):
 *    - ToggleSetting: Updates and immediately persists (for menu toggles)
 *    - SetActivePreset: Changes active preset and auto-saves
 *    - UpdateServiceInActivePreset: Changes service selection and auto-saves
 *    - CreatePreset, DeletePreset, RenamePreset: Preset management (auto-save)
 */
sealed interface SettingsIntent : UiIntent {

    // ============================================================
    // Draft Mode (Manual Save)
    // ============================================================

    /**
     * Updates the working configuration without persisting.
     * Used in settings dialog where user can cancel changes.
     *
     * @property newConfiguration The new draft configuration
     */
    data class UpdateDraft(val newConfiguration: Configuration) : SettingsIntent

    /**
     * Persists the current working configuration.
     * After success, working becomes the new original.
     */
    data object SaveChanges : SettingsIntent

    /**
     * Discards changes by reverting working to original.
     * Typically triggered by "Cancel" button in settings dialog.
     */
    data object CancelChanges : SettingsIntent

    /**
     * Resets working configuration to application defaults.
     * User must still click "Save" to persist.
     */
    data object ResetToDefaults : SettingsIntent

    // ============================================================
    // Quick Actions (Auto-Save)
    // ============================================================

    /**
     * Applies a quick setting toggle and immediately persists.
     * Used for menu checkboxes and toolbar toggles.
     *
     * Example:
     * ```
     * ToggleSetting { it.copy(isSpellCheckingEnabled = !it.isSpellCheckingEnabled) }
     * ```
     *
     * @property update Transform function to apply to current configuration
     */
    data class ToggleSetting(
        val update: (Configuration) -> Configuration
    ) : SettingsIntent

    // ============================================================
    // Service Preset Management (Auto-Save)
    // ============================================================

    /**
     * Changes the globally active service preset.
     * This immediately persists to ensure service selection is saved.
     *
     * @property presetId The ID of the preset to activate
     */
    data class SetActivePreset(val presetId: String) : SettingsIntent

    /**
     * Updates a service selection in the currently active preset.
     * This immediately persists to ensure service selection is saved.
     *
     * @property type The type of service (e.g., TRANSLATOR, TTS)
     * @property serviceId The ID of the service to select, or null to clear
     */
    data class UpdateServiceInActivePreset(
        val type: ServiceType,
        val serviceId: String?
    ) : SettingsIntent

    /**
     * Creates a new service preset with default services.
     * The new preset becomes active and changes are persisted.
     *
     * @property name The display name for the new preset
     */
    data class CreatePreset(val name: String) : SettingsIntent

    /**
     * Deletes a service preset.
     * Cannot delete the last remaining preset.
     * If deleting the active preset, the first remaining preset becomes active.
     * Changes are immediately persisted.
     *
     * @property presetId The ID of the preset to delete
     */
    data class DeletePreset(val presetId: String) : SettingsIntent

    /**
     * Renames a service preset.
     * Changes are immediately persisted.
     *
     * @property presetId The ID of the preset to rename
     * @property newName The new display name
     */
    data class RenamePreset(
        val presetId: String,
        val newName: String
    ) : SettingsIntent
}
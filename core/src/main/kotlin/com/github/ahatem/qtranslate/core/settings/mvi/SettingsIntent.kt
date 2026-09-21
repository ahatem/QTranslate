package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.arch.UiIntent

/**
 * All user actions that can be dispatched to [SettingsStore].
 *
 * Intents fall into two behavioural categories:
 *
 * ### Draft mode (manual save)
 * Used by the settings dialog where the user edits freely and must explicitly
 * confirm or cancel their changes:
 * - [UpdateDraft] — updates the working copy without persisting
 * - [SaveChanges] — persists the working copy
 * - [CancelChanges] — reverts to the last saved configuration and closes the dialog
 * - [ResetToDefaults] — replaces the working copy with [Configuration.DEFAULT]
 *
 * ### Quick actions (scoped auto-save)
 * Used by toolbar toggles, menu items, and the service selector panel where
 * changes take effect immediately and are persisted without a confirmation step:
 * - [ToggleSetting] — persists only the setting changed by the external action
 *
 * Settings-dialog operations remain draft-only until [SaveChanges].
 */
sealed interface SettingsIntent : UiIntent {

    // ---- Draft mode ----

    /**
     * Updates the working configuration without persisting.
     * [isDirty][SettingsState.isDirty] becomes `true` if [newConfiguration] differs
     * from the original.
     */
    data class UpdateDraft(val newConfiguration: Configuration) : SettingsIntent

    /** Persists the current working configuration. On success, working becomes the new original. */
    data object SaveChanges : SettingsIntent

    /**
     * Discards unsaved changes by reverting the working copy to the original.
     * Also closes the settings dialog via [SettingsEvent.CloseSettingsDialog].
     */
    data object CancelChanges : SettingsIntent

    /**
     * Replaces the working configuration with [Configuration.DEFAULT].
     * The user must still dispatch [SaveChanges] to persist.
     */
    data object ResetToDefaults : SettingsIntent

    // ---- External quick actions ----

    /**
     * Applies [update] to the last persisted configuration and immediately saves it.
     * An unrelated dirty settings-dialog draft is preserved and is not committed.
     *
     * Use this for menu checkboxes and toolbar toggles where changes take effect instantly.
     *
     * Example:
     * ```kotlin
     * store.dispatch(SettingsIntent.ToggleSetting {
     *     it.copy(isSpellCheckingEnabled = !it.isSpellCheckingEnabled)
     * })
     * ```
     */
    data class ToggleSetting(val update: (Configuration) -> Configuration) : SettingsIntent

    /**
     * Switches the active service preset in the settings draft.
     */
    data class SetActivePreset(val presetId: String) : SettingsIntent

    /**
     * Selects [serviceId] for [type] in the active preset draft.
     * Pass `null` for [serviceId] to clear the selection (fall back to first available).
     */
    data class UpdateServiceInActivePreset(
        val type: ServiceRole,
        val serviceId: String?
    ) : SettingsIntent

    /**
     * Creates a new preset named [name] with default Google services pre-selected and makes it active.
     */
    data class CreatePreset(val name: String) : SettingsIntent

    /**
     * Deletes the preset identified by [presetId].
     * Cannot delete the last remaining preset — dispatching this intent when only
     * one preset exists sends [SettingsEvent.ShowMessage] with an error.
     * If the deleted preset was active, the first remaining preset becomes active.
     */
    data class DeletePreset(val presetId: String) : SettingsIntent

    /**
     * Renames the preset identified by [presetId] to [newName].
     */
    data class RenamePreset(val presetId: String, val newName: String) : SettingsIntent

    /** Adds a new translation rule to the settings draft. */
    data class AddTranslationRule(val rule: TranslationRule) : SettingsIntent

    /** Removes an existing translation rule from the settings draft. */
    data class RemoveTranslationRule(val rule: TranslationRule) : SettingsIntent
}

package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
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
 * ### Quick actions (auto-save)
 * Used by toolbar toggles, menu items, and the service selector panel where
 * changes take effect immediately and are persisted without a confirmation step:
 * - [ToggleSetting] — applies an arbitrary transform and immediately saves
 * - [SetActivePreset] — switches the active preset and saves
 * - [UpdateServiceInActivePreset] — changes a service selection and saves
 * - [CreatePreset], [DeletePreset], [RenamePreset] — preset CRUD, all auto-saved
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

    // ---- Quick actions ----

    /**
     * Applies [update] to the current working configuration and immediately saves.
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
     * Switches the active service preset to [presetId] and immediately saves.
     * Emits [AppEvent.ActivePresetChanged] on the event bus.
     */
    data class SetActivePreset(val presetId: String) : SettingsIntent

    /**
     * Selects [serviceId] for [type] in the active preset and immediately saves.
     * Pass `null` for [serviceId] to clear the selection (fall back to first available).
     * Emits [AppEvent.ServiceSelectionChanged] on the event bus.
     */
    data class UpdateServiceInActivePreset(
        val type: ServiceType,
        val serviceId: String?
    ) : SettingsIntent

    /**
     * Creates a new preset named [name] with default Google services pre-selected,
     * makes it active, and immediately saves.
     * Emits [AppEvent.ActivePresetChanged] on the event bus.
     */
    data class CreatePreset(val name: String) : SettingsIntent

    /**
     * Deletes the preset identified by [presetId] and immediately saves.
     * Cannot delete the last remaining preset — dispatching this intent when only
     * one preset exists sends [SettingsEvent.ShowMessage] with an error.
     * If the deleted preset was active, the first remaining preset becomes active
     * and [AppEvent.ActivePresetChanged] is emitted.
     */
    data class DeletePreset(val presetId: String) : SettingsIntent

    /**
     * Renames the preset identified by [presetId] to [newName] and immediately saves.
     */
    data class RenamePreset(val presetId: String, val newName: String) : SettingsIntent

    /** Adds a new translation rule and immediately saves. */
    data class AddTranslationRule(val rule: TranslationRule) : SettingsIntent

    /** Removes an existing translation rule and immediately saves. */
    data class RemoveTranslationRule(val rule: TranslationRule) : SettingsIntent
}
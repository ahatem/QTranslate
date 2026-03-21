package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * UI state for the settings subsystem.
 *
 * ### Draft / working pattern
 * The store maintains two versions of the configuration at all times:
 * - [originalConfiguration] — the last version successfully persisted to disk.
 *   This is the authoritative source of truth.
 * - [workingConfiguration] — the version currently being edited in the settings dialog.
 *   Starts equal to [originalConfiguration] and diverges as the user makes changes.
 * - [isDirty] — `true` when [workingConfiguration] differs from [originalConfiguration],
 *   indicating there are unsaved changes. Used to enable/disable the Save button
 *   and to show an "unsaved changes" indicator in the UI.
 *
 * ### Lifecycle
 * 1. On open: `workingConfiguration = originalConfiguration`, `isDirty = false`.
 * 2. On edit: `workingConfiguration` updates, `isDirty` becomes `true`.
 * 3. On save: `originalConfiguration = workingConfiguration`, `isDirty = false`.
 * 4. On cancel: `workingConfiguration = originalConfiguration`, `isDirty = false`.
 *
 * @property isSaving `true` while a save operation is in progress. Used to disable
 *   the Save button and show a loading indicator.
 * @property originalConfiguration The last persisted configuration.
 * @property workingConfiguration The configuration currently being edited.
 * @property isDirty Whether there are unsaved changes.
 * @property loadError A human-readable error message if the initial configuration
 *   load failed and the app fell back to [Configuration.DEFAULT]. `null` if load
 *   succeeded. The UI should display a warning banner when this is non-null.
 */
data class SettingsState(
    val isSaving: Boolean = false,
    val originalConfiguration: Configuration,
    val workingConfiguration: Configuration,
    val isDirty: Boolean = false,
    val loadError: String? = null
) : UiState {

    companion object {
        /**
         * Creates the initial state from a successfully loaded configuration.
         * Both original and working start equal — no unsaved changes.
         */
        fun initial(config: Configuration): SettingsState = SettingsState(
            isSaving = false,
            originalConfiguration = config,
            workingConfiguration = config,
            isDirty = false,
            loadError = null
        )

        /**
         * Creates the initial state when loading failed and the app fell back to defaults.
         * The [loadError] message will be shown as a warning banner in the UI.
         */
        fun initialWithError(errorMessage: String): SettingsState = SettingsState(
            isSaving = false,
            originalConfiguration = Configuration.DEFAULT,
            workingConfiguration = Configuration.DEFAULT,
            isDirty = false,
            loadError = errorMessage
        )
    }
}
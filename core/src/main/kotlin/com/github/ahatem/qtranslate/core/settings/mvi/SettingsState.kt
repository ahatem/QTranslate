package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * UI state for settings management.
 *
 * This implements the "draft/working" pattern:
 * - originalConfiguration: Last persisted version (source of truth)
 * - workingConfiguration: User's current edits (draft)
 * - isDirty: Whether working differs from original
 *
 * This pattern allows:
 * 1. User can edit settings without immediately persisting
 * 2. "Cancel" button can revert to original
 * 3. "Save" button persists working to original
 * 4. Clear visual indication of unsaved changes
 *
 * @property isSaving Whether a save operation is in progress
 * @property originalConfiguration The last saved/persisted configuration
 * @property workingConfiguration The configuration being edited (draft)
 * @property isDirty Whether there are unsaved changes
 */
data class SettingsState(
    val isSaving: Boolean = false,
    val originalConfiguration: Configuration,
    val workingConfiguration: Configuration,
    val isDirty: Boolean = false
) : UiState {

    companion object {
        /**
         * Creates initial state from a loaded configuration.
         * Both original and working start as the same (no changes yet).
         */
        fun initial(config: Configuration) = SettingsState(
            isSaving = false,
            originalConfiguration = config,
            workingConfiguration = config,
            isDirty = false
        )
    }
}
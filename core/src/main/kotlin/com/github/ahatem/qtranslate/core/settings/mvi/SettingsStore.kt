package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.*
import com.github.ahatem.qtranslate.core.shared.arch.Store
import com.github.ahatem.qtranslate.core.shared.events.AppEvent
import com.github.ahatem.qtranslate.core.shared.events.AppEventBus
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

/**
 * Store managing application settings and configuration.
 *
 * This store implements the draft/working pattern:
 * - User edits create a "working" copy (draft)
 * - "Save" persists the working copy
 * - "Cancel" reverts to the original (last saved)
 *
 * The store also handles:
 * - Service preset management (create, delete, rename, switch)
 * - Auto-saving for quick actions (toggles, preset switches)
 * - Event emission for cross-store communication
 *
 * @property settingsRepository Persistence layer for configuration
 * @property eventBus Event bus for emitting domain events
 * @property logger Logger instance for this store
 * @property scope Coroutine scope for async operations
 * @property initialConfiguration Initial configuration loaded at startup
 */
class SettingsStore(
    private val settingsRepository: SettingsRepository,
    private val eventBus: AppEventBus,
    private val logger: Logger,
    private val scope: CoroutineScope,
    initialConfiguration: Configuration
) : Store<SettingsState, SettingsIntent, SettingsEvent> {

    private val _state = MutableStateFlow(SettingsState.initial(initialConfiguration))
    override val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    override val events: Flow<SettingsEvent> = _eventChannel.receiveAsFlow()

    init {
        logger.info("SettingsStore initialized")
        observeRepositoryChanges()
    }

    /**
     * Observes configuration changes from the repository.
     *
     * This handles external configuration updates (e.g., from file sync, other instances).
     * The working configuration is only updated if the user hasn't made local changes.
     */
    private fun observeRepositoryChanges() {
        scope.launch {
            settingsRepository.configuration
                .distinctUntilChanged()
                .collect { persistedConfig ->
                    logger.debug("Repository configuration changed")

                    _state.update { currentState ->
                        val shouldUpdateWorking = !currentState.isDirty

                        currentState.copy(
                            originalConfiguration = persistedConfig,
                            workingConfiguration = if (shouldUpdateWorking) {
                                logger.debug("Updating working configuration from repository")
                                persistedConfig
                            } else {
                                logger.debug("Preserving working configuration (user has unsaved changes)")
                                currentState.workingConfiguration
                            },
                            isDirty = if (shouldUpdateWorking) {
                                false
                            } else {
                                currentState.workingConfiguration != persistedConfig
                            }
                        )
                    }
                }
        }
    }

    override fun dispatch(intent: SettingsIntent) {
        logger.debug("Dispatching intent: ${intent::class.simpleName}")

        when (intent) {
            is SettingsIntent.UpdateDraft -> handleUpdateDraft(intent.newConfiguration)
            SettingsIntent.SaveChanges -> saveChanges()
            SettingsIntent.CancelChanges -> cancelChanges()
            SettingsIntent.ResetToDefaults -> resetToDefaults()
            is SettingsIntent.ToggleSetting -> handleToggleSetting(intent.update)
            is SettingsIntent.SetActivePreset -> handleSetActivePreset(intent.presetId)
            is SettingsIntent.UpdateServiceInActivePreset -> handleUpdateServiceInActivePreset(intent)
            is SettingsIntent.CreatePreset -> handleCreatePreset(intent.name)
            is SettingsIntent.DeletePreset -> handleDeletePreset(intent.presetId)
            is SettingsIntent.RenamePreset -> handleRenamePreset(intent.presetId, intent.newName)
        }
    }

    // ============================================================
    // Draft Mode Handlers (Manual Save)
    // ============================================================

    /**
     * Updates the working configuration without persisting.
     * Sets isDirty flag if working differs from original.
     */
    private fun handleUpdateDraft(newConfiguration: Configuration) {
        _state.update { currentState ->
            val isDirty = newConfiguration != currentState.originalConfiguration

            logger.debug("Draft updated, isDirty=$isDirty")

            currentState.copy(
                workingConfiguration = newConfiguration,
                isDirty = isDirty
            )
        }
    }

    /**
     * Persists the working configuration to storage.
     * On success, working becomes the new original.
     */
    private fun saveChanges() {
        val currentState = _state.value

        if (currentState.isSaving) {
            logger.warn("Save already in progress, skipping duplicate save request")
            return
        }

        if (!currentState.isDirty) {
            logger.debug("No changes to save")
            return
        }

        logger.info("Saving configuration changes...")

        scope.launch {
            _state.update { it.copy(isSaving = true) }

            try {
                val result = settingsRepository.updateConfiguration(currentState.workingConfiguration)

                result.fold(
                    success = {
                        logger.info("Configuration saved successfully")

                        // Update state: working is now the original
                        _state.update {
                            it.copy(
                                originalConfiguration = it.workingConfiguration,
                                isDirty = false,
                                isSaving = false
                            )
                        }

                        // Emit domain event
                        eventBus.emit(AppEvent.ConfigurationSaved(currentState.workingConfiguration))

                        // Notify UI
                        _eventChannel.send(
                            SettingsEvent.ShowMessage(
                                "Settings saved successfully",
                                NotificationType.SUCCESS
                            )
                        )
                    },
                    failure = { error ->
                        logger.error("Failed to save configuration: ${error.message}")

                        _state.update { it.copy(isSaving = false) }

                        _eventChannel.send(
                            SettingsEvent.ShowMessage(
                                "Failed to save settings: ${error.message}",
                                NotificationType.ERROR
                            )
                        )
                    }
                )
            } catch (e: Exception) {
                logger.error("Unexpected error during save", e)

                _state.update { it.copy(isSaving = false) }

                _eventChannel.send(
                    SettingsEvent.ShowMessage(
                        "Unexpected error: ${e.message ?: "Unknown error"}",
                        NotificationType.ERROR
                    )
                )
            }
        }
    }

    /**
     * Cancels unsaved changes by reverting working to original.
     */
    private fun cancelChanges() {
        logger.info("Canceling configuration changes")

        _state.update { currentState ->
            currentState.copy(
                workingConfiguration = currentState.originalConfiguration,
                isDirty = false
            )
        }

        scope.launch {
            _eventChannel.send(SettingsEvent.CloseSettingsDialog)
        }
    }

    /**
     * Resets working configuration to application defaults.
     * User must still save to persist.
     */
    private fun resetToDefaults() {
        logger.info("Resetting configuration to defaults")

        _state.update { currentState ->
            val isDirty = Configuration.DEFAULT != currentState.originalConfiguration

            currentState.copy(
                workingConfiguration = Configuration.DEFAULT,
                isDirty = isDirty
            )
        }
    }

    // ============================================================
    // Quick Action Handlers (Auto-Save)
    // ============================================================

    /**
     * Applies a quick setting toggle and auto-saves.
     * Used for menu checkboxes and toolbar toggles.
     */
    private fun handleToggleSetting(update: (Configuration) -> Configuration) {
        logger.debug("Handling toggle setting")

        val updated = update(_state.value.workingConfiguration)

        _state.update {
            it.copy(
                workingConfiguration = updated,
                isDirty = updated != it.originalConfiguration
            )
        }

        saveChanges()
    }

    /**
     * Changes the active preset and auto-saves.
     */
    private fun handleSetActivePreset(presetId: String) {
        logger.info("Setting active preset: $presetId")

        val currentConfig = _state.value.workingConfiguration
        val preset = currentConfig.servicePresets.find { it.id == presetId }

        if (preset == null) {
            logger.warn("Preset not found: $presetId")
            return
        }

        val updated = currentConfig.withActivePresetId(presetId)

        _state.update {
            it.copy(
                workingConfiguration = updated,
                isDirty = updated != it.originalConfiguration
            )
        }

        // Emit event before saving
        scope.launch {
            eventBus.emit(AppEvent.ActivePresetChanged(presetId, preset.name))
        }

        saveChanges()
    }

    /**
     * Updates a service selection in the active preset and auto-saves.
     */
    private fun handleUpdateServiceInActivePreset(intent: SettingsIntent.UpdateServiceInActivePreset) {
        logger.info("Updating service in active preset: ${intent.type} -> ${intent.serviceId}")

        val currentConfig = _state.value.workingConfiguration
        val activePresetId = currentConfig.activeServicePresetId

        if (activePresetId == null) {
            logger.warn("No active preset, cannot update service")
            return
        }

        val updated = currentConfig.withServiceSelection(intent.type, intent.serviceId)

        _state.update {
            it.copy(
                workingConfiguration = updated,
                isDirty = updated != it.originalConfiguration
            )
        }

        // Emit event before saving
        scope.launch {
            eventBus.emit(AppEvent.ServiceSelectionChanged(intent.type, intent.serviceId))
        }

        saveChanges()
    }

    // ============================================================
    // Preset Management Handlers (Auto-Save)
    // ============================================================

    /**
     * Creates a new preset with default services.
     * The new preset becomes active and is auto-saved.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun handleCreatePreset(name: String) {
        logger.info("Creating new preset: $name")

        val currentConfig = _state.value.workingConfiguration
        val newPreset = ServicePreset.createDefault(name)

        val updated = currentConfig
            .withPreset(newPreset)
            .withActivePresetId(newPreset.id)

        _state.update {
            it.copy(
                workingConfiguration = updated,
                isDirty = updated != it.originalConfiguration
            )
        }

        scope.launch {
            eventBus.emit(AppEvent.ActivePresetChanged(newPreset.id, newPreset.name))
        }

        saveChanges()
    }

    /**
     * Deletes a preset.
     * Cannot delete the last preset.
     * If deleting active preset, activates the first remaining preset.
     */
    private fun handleDeletePreset(presetId: String) {
        logger.info("Deleting preset: $presetId")

        val currentConfig = _state.value.workingConfiguration

        if (currentConfig.servicePresets.size <= 1) {
            logger.warn("Cannot delete last preset")
            scope.launch {
                _eventChannel.send(
                    SettingsEvent.ShowMessage(
                        "Cannot delete the last preset",
                        NotificationType.ERROR
                    )
                )
            }
            return
        }

        val updated = currentConfig.withoutPreset(presetId)

        _state.update {
            it.copy(
                workingConfiguration = updated,
                isDirty = updated != it.originalConfiguration
            )
        }

        // If active preset changed, emit event
        if (updated.activeServicePresetId != currentConfig.activeServicePresetId) {
            val newActivePreset = updated.getActivePreset()
            if (newActivePreset != null) {
                scope.launch {
                    eventBus.emit(
                        AppEvent.ActivePresetChanged(newActivePreset.id, newActivePreset.name)
                    )
                }
            }
        }

        saveChanges()
    }

    /**
     * Renames a preset and auto-saves.
     */
    private fun handleRenamePreset(presetId: String, newName: String) {
        logger.info("Renaming preset $presetId to: $newName")

        val currentConfig = _state.value.workingConfiguration
        val updated = currentConfig.withPresetRenamed(presetId, newName)

        _state.update {
            it.copy(
                workingConfiguration = updated,
                isDirty = updated != it.originalConfiguration
            )
        }

        saveChanges()
    }
}
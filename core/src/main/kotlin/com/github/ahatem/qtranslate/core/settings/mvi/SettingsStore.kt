package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.arch.Store
import com.github.ahatem.qtranslate.core.shared.events.AppEvent
import com.github.ahatem.qtranslate.core.shared.events.AppEventBus
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MVI store managing application configuration and settings.
 *
 * ### Responsibilities
 * - Maintaining the draft/working configuration pattern (see [SettingsState])
 * - Persisting changes via [SettingsRepository]
 * - Emitting domain events via [AppEventBus] on significant state changes
 * - Delegating all preset CRUD operations to [PresetManager]
 *
 * ### Threading
 * [dispatch] is intentionally synchronous and non-suspending so the UI can call
 * it freely from the event thread. Draft updates mutate [_state] directly via
 * [MutableStateFlow.update] (which is thread-safe). Save operations launch on
 * [scope] and are serialized by [saveMutex] to prevent concurrent saves.
 *
 * @property settingsRepository Persistence layer for [Configuration].
 * @property eventBus Application-wide event bus for cross-store communication.
 * @property logger Logger scoped to this store.
 * @property scope Coroutine scope for all async operations.
 * @property initialConfiguration The configuration loaded at startup, used to
 *   populate the initial [SettingsState].
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

    // Serializes save operations — prevents two concurrent save coroutines from
    // both seeing isSaving=false and racing to write conflicting configurations.
    private val saveMutex = Mutex()

    private val presetManager = PresetManager(
        applyUpdate = { newConfig -> applyWorkingUpdate(newConfig) },
        eventBus = eventBus,
        eventChannel = _eventChannel,
        scope = scope,
        logger = logger
    )

    init {
        logger.info("SettingsStore initialized")
        observeRepositoryChanges()
    }

    // -------------------------------------------------------------------------
    // Repository observation
    // -------------------------------------------------------------------------

    /**
     * Observes configuration changes from the repository and syncs the store state.
     *
     * The working copy is only updated when the user has no unsaved changes ([isDirty] = false).
     * This handles external updates (e.g. config file edited outside the app, or another
     * coroutine updating the repository directly).
     */
    private fun observeRepositoryChanges() {
        scope.launch {
            settingsRepository.configuration
                .distinctUntilChanged()
                .collect { persisted ->
                    _state.update { current ->
                        current.copy(
                            originalConfiguration = persisted,
                            workingConfiguration = if (!current.isDirty) {
                                logger.debug("Syncing working config from repository")
                                persisted
                            } else {
                                logger.debug("Preserving working config — user has unsaved changes")
                                current.workingConfiguration
                            },
                            isDirty = if (!current.isDirty) false
                            else current.workingConfiguration != persisted
                        )
                    }
                }
        }
    }

    // -------------------------------------------------------------------------
    // Intent dispatch
    // -------------------------------------------------------------------------

    override fun dispatch(intent: SettingsIntent) {
        logger.debug("Dispatching: ${intent::class.simpleName}")
        when (intent) {
            // Draft mode
            is SettingsIntent.UpdateDraft    -> handleUpdateDraft(intent.newConfiguration)
            SettingsIntent.SaveChanges       -> launchSave()
            SettingsIntent.CancelChanges     -> handleCancelChanges()
            SettingsIntent.ResetToDefaults   -> handleResetToDefaults()

            // Quick actions — update working copy then trigger save
            is SettingsIntent.ToggleSetting  -> {
                applyWorkingUpdate(intent.update(_state.value.workingConfiguration))
                launchSave()
            }

            // Preset operations — delegated to PresetManager, auto-save after
            is SettingsIntent.SetActivePreset ->
                presetManager.setActivePreset(_state.value.workingConfiguration, intent.presetId)
                    .also { launchSave() }

            is SettingsIntent.UpdateServiceInActivePreset ->
                presetManager.updateServiceInActivePreset(_state.value.workingConfiguration, intent)
                    .also { launchSave() }

            is SettingsIntent.CreatePreset ->
                presetManager.createPreset(_state.value.workingConfiguration, intent.name)
                    .also { launchSave() }

            is SettingsIntent.DeletePreset ->
                presetManager.deletePreset(_state.value.workingConfiguration, intent.presetId)
                    .also { launchSave() }

            is SettingsIntent.RenamePreset ->
                presetManager.renamePreset(_state.value.workingConfiguration, intent.presetId, intent.newName)
                    .also { launchSave() }
        }
    }

    // -------------------------------------------------------------------------
    // Draft mode handlers
    // -------------------------------------------------------------------------

    private fun handleUpdateDraft(newConfiguration: Configuration) {
        _state.update { current ->
            current.copy(
                workingConfiguration = newConfiguration,
                isDirty = newConfiguration != current.originalConfiguration
            ).also { logger.debug("Draft updated, isDirty=${it.isDirty}") }
        }
    }

    private fun handleCancelChanges() {
        logger.info("Cancelling changes — reverting to original")
        val original = _state.value.originalConfiguration
        _state.update { it.copy(workingConfiguration = it.originalConfiguration, isDirty = false) }
        // Emit ChangesReverted so the dialog can revert any live-preview side
        // effects (e.g. language or theme applied before saving) and then close.
        scope.launch { _eventChannel.send(SettingsEvent.ChangesReverted(original)) }
    }

    private fun handleResetToDefaults() {
        logger.info("Resetting working configuration to defaults")
        _state.update { current ->
            current.copy(
                workingConfiguration = Configuration.DEFAULT,
                isDirty = Configuration.DEFAULT != current.originalConfiguration
            )
        }
    }

    // -------------------------------------------------------------------------
    // Working copy update (shared by all handlers and PresetManager)
    // -------------------------------------------------------------------------

    /**
     * Applies [newConfig] as the new working configuration and marks the state dirty
     * if it differs from the original. Called by both internal handlers and [PresetManager].
     */
    private fun applyWorkingUpdate(newConfig: Configuration) {
        _state.update { current ->
            current.copy(
                workingConfiguration = newConfig,
                isDirty = newConfig != current.originalConfiguration
            )
        }
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Launches a save coroutine on [scope].
     *
     * [saveMutex] ensures only one save runs at a time — if a save is already in
     * progress, the new call waits for it to finish and then re-evaluates whether
     * there is still anything to save (the in-flight save may have already captured
     * the latest changes).
     */
    private fun launchSave() {
        scope.launch {
            saveMutex.withLock {
                // Re-read state inside the coroutine and inside the lock —
                // this is the fix for the stale-state race condition.
                val current = _state.value

                if (!current.isDirty) {
                    logger.debug("Nothing to save — skipping")
                    return@withLock
                }

                // Capture the config we intend to save while holding the lock
                val configToSave = current.workingConfiguration

                logger.info("Saving configuration...")
                _state.update { it.copy(isSaving = true) }

                settingsRepository.updateConfiguration(configToSave).fold(
                    success = {
                        logger.info("Configuration saved successfully")
                        _state.update {
                            it.copy(
                                originalConfiguration = configToSave,
                                isDirty = it.workingConfiguration != configToSave,
                                isSaving = false
                            )
                        }
                        eventBus.emit(AppEvent.ConfigurationSaved(configToSave))
                        _eventChannel.send(
                            SettingsEvent.ShowMessage("Settings saved", NotificationType.SUCCESS)
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
            }
        }
    }
}
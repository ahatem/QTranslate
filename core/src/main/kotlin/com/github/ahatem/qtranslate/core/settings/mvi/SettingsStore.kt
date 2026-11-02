package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.SettingsError
import com.github.ahatem.qtranslate.core.settings.data.withoutPreset
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.Ok
import com.github.ahatem.qtranslate.core.shared.arch.Store
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * MVI store managing application configuration and settings.
 *
 * ### Responsibilities
 * - Maintaining the draft/working configuration pattern (see [SettingsState])
 * - Persisting changes via [SettingsRepository]
 * - Delegating all preset CRUD operations to [PresetManager]
 *
 * ### Threading
 * [dispatch] is intentionally synchronous and non-suspending so the UI can call
 * it freely from the event thread. Draft updates mutate [_state] directly via
 * [MutableStateFlow.update] (which is thread-safe). Save operations launch on
 * [scope] and are serialized by [saveMutex] to prevent concurrent saves.
 *
 * @property settingsRepository Persistence layer for [Configuration].
 * @property logger Logger scoped to this store.
 * @property scope Coroutine scope for all async operations.
 * @property initialConfiguration The configuration loaded at startup, used to
 *   populate the initial [SettingsState].
 */
class SettingsStore(
    private val settingsRepository: SettingsRepository,
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
    private val committedRevision = AtomicLong()
    private val acceptedLock = Any()
    private val accepted = mutableSetOf<Job>()
    private var closing = false
    private var lastAccepted: Job? = null

    private fun <T> accept(block: suspend () -> T): Deferred<T> = synchronized(acceptedLock) {
        check(!closing) { "Settings persistence is shutting down" }
        val previous = lastAccepted
        val job = scope.async(start = CoroutineStart.LAZY) {
            previous?.join()
            block()
        }
        lastAccepted = job
        accepted.add(job)
        job.invokeOnCompletion { synchronized(acceptedLock) { accepted.remove(job) } }
        job.start()
        job
    }

    /** Stop accepting saves and wait for accepted operations before cancelling storage. */
    suspend fun drainAcceptedSaves(timeoutMillis: Long = 10_000): Boolean {
        val pending = synchronized(acceptedLock) {
            closing = true
            accepted.toList()
        }
        return withTimeoutOrNull(timeoutMillis) { pending.joinAll(); true } ?: false
    }

    private val presetManager = PresetManager(
        applyUpdate = { transform -> applyWorkingUpdate(transform) },
        delete = { id ->
            val previous = _state.getAndUpdate { current ->
                val config = current.workingConfiguration
                val updated = if (config.servicePresets.size > 1) config.withoutPreset(id) else config
                current.copy(workingConfiguration = updated, isDirty = updated != current.originalConfiguration)
            }
            previous.workingConfiguration.servicePresets.size > 1
        },
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
                    val observedRevision = committedRevision.get()
                    saveMutex.withLock {
                    if (observedRevision != committedRevision.get()) return@withLock
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
    }

    // -------------------------------------------------------------------------
    // Intent dispatch
    // -------------------------------------------------------------------------

    override fun dispatch(intent: SettingsIntent) {
        logger.debug("Dispatching: ${intent::class.simpleName}")
        when (intent) {
            // Draft mode
            is SettingsIntent.UpdateDraft    -> handleUpdateDraft(intent.newConfiguration)
            is SettingsIntent.TransformDraft -> applyWorkingUpdate(intent.transform)
            SettingsIntent.SaveChanges       -> launchSave()
            SettingsIntent.CancelChanges     -> handleCancelChanges()
            SettingsIntent.ResetToDefaults   -> handleResetToDefaults()

            // Quick actions persist only their scoped update. A settings dialog may have a
            // separate dirty draft that must remain uncommitted.
            is SettingsIntent.ToggleSetting  -> {
                launchScopedSave(intent.update, intent.onSuccess)
            }

            // Preset operations update the settings draft and wait for Apply/OK.
            is SettingsIntent.SetActivePreset ->
                presetManager.setActivePreset(intent.presetId)

            is SettingsIntent.UpdateServiceInActivePreset ->
                presetManager.updateServiceInActivePreset(intent)

            is SettingsIntent.AddTranslatorToActivePreset ->
                presetManager.addTranslator(intent)

            is SettingsIntent.RemoveTranslatorFromActivePreset ->
                presetManager.removeTranslator(intent)

            is SettingsIntent.MoveTranslatorInActivePreset ->
                presetManager.moveTranslator(intent)

            is SettingsIntent.PromoteTranslatorToPrimary ->
                presetManager.promoteTranslatorToPrimary(intent)

            is SettingsIntent.CreatePreset ->
                presetManager.createPreset(intent.name)

            is SettingsIntent.DeletePreset ->
                presetManager.deletePreset(intent.presetId)

            is SettingsIntent.RenamePreset ->
                presetManager.renamePreset(intent.presetId, intent.newName)

            is SettingsIntent.AddTranslationRule -> applyWorkingUpdate { current ->
                current.copy(translationRules = current.translationRules + intent.rule)
            }
            is SettingsIntent.RemoveTranslationRule -> applyWorkingUpdate { current ->
                current.copy(translationRules = current.translationRules - intent.rule)
            }
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
        _state.update { it.copy(workingConfiguration = it.originalConfiguration, isDirty = false) }
        scope.launch { _eventChannel.send(SettingsEvent.CloseSettingsDialog) }
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
    private fun applyWorkingUpdate(transform: (Configuration) -> Configuration) {
        _state.update { current ->
            val newConfig = transform(current.workingConfiguration)
            current.copy(workingConfiguration = newConfig, isDirty = newConfig != current.originalConfiguration)
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
        val receipt = submitDraftSave()
        scope.launch {
            receipt.await().fold(
                success = { _eventChannel.send(SettingsEvent.ShowMessage("Settings saved", NotificationType.SUCCESS)) },
                failure = { error -> _eventChannel.send(SettingsEvent.ShowMessage("Failed to save settings: ${error.message}", NotificationType.ERROR)) }
            )
        }
    }

    /** Accepts the complete save sequence in the application scope, independent of its waiter. */
    fun submitDraftSave(afterCommit: suspend () -> Unit = {}): Deferred<Result<Unit, SettingsError>> {
        val snapshot = _state.value
        return accept {
        saveMutex.withLock {
            val result = if (!snapshot.isDirty) Ok(Unit) else {
                val configToSave = snapshot.workingConfiguration
                _state.update { it.copy(isSaving = true) }
                settingsRepository.updateConfiguration(configToSave).also { saved ->
                    saved.fold(
                        success = {
                            committedRevision.incrementAndGet()
                            _state.update { it.copy(originalConfiguration = configToSave,
                                isDirty = it.workingConfiguration != configToSave, isSaving = false) }
                        },
                        failure = {
                            logger.error("Failed to save configuration")
                            _state.update { it.copy(isSaving = false) }
                        }
                    )
                }
            }
            result.fold(success = { afterCommit() }, failure = {})
            result
        }
        }
    }

    /** Waits for the result of this accepted request. */
    suspend fun saveDraftAndAwait(): Result<Unit, SettingsError> = submitDraftSave().await()

    /** Persists an external quick action without committing an unrelated settings draft. */
    private fun launchScopedSave(
        update: (Configuration) -> Configuration,
        onSuccess: (Configuration) -> Unit
    ) {
        accept { saveMutex.withLock { performScopedSave(update, onSuccess, notify = true) } }
    }

    /**
     * Same persistence as [SettingsIntent.ToggleSetting], but suspends until the write
     * completes instead of firing on [scope] and returning immediately.
     *
     * For a caller that must know the write finished before moving on, such as application
     * shutdown persisting the final window bounds, rather than racing an independent
     * [scope]-launched save.
     *
     * @return true if the configuration was persisted, false on failure.
     */
    suspend fun persistScopedUpdateAndAwait(update: (Configuration) -> Configuration): Boolean =
        saveMutex.withLock { performScopedSave(update, onSuccess = {}, notify = false) }

    private suspend fun performScopedSave(
        update: (Configuration) -> Configuration,
        onSuccess: (Configuration) -> Unit,
        notify: Boolean
    ): Boolean {
        val current = _state.value
        val configToSave = update(current.originalConfiguration)
        logger.info("Saving scoped configuration update...")
        _state.update { it.copy(isSaving = true) }
        return settingsRepository.updateConfiguration(configToSave).fold(
            success = {
                committedRevision.incrementAndGet()
                _state.update { current ->
                    val workingConfiguration = if (current.isDirty) {
                        update(current.workingConfiguration)
                    } else {
                        configToSave
                    }
                    current.copy(
                        originalConfiguration = configToSave,
                        workingConfiguration = workingConfiguration,
                        isDirty = workingConfiguration != configToSave,
                        isSaving = false
                    )
                }
                onSuccess(configToSave)
                if (notify) {
                    scope.launch { _eventChannel.send(
                        SettingsEvent.ShowMessage("Settings saved", NotificationType.SUCCESS)
                    ) }
                }
                true
            },
            failure = { error ->
                logger.error("Failed to save configuration")
                _state.update { it.copy(isSaving = false) }
                if (notify) {
                    scope.launch { _eventChannel.send(
                        SettingsEvent.ShowMessage(
                            "Failed to save settings: ${error.message}",
                            NotificationType.ERROR
                        )
                    ) }
                }
                false
            }
        )
    }
}

package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.arch.Store
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsStore(
    private val settingsRepository: SettingsRepository,
    private val pluginManager: PluginManager,
    private val scope: CoroutineScope
) : Store<SettingsState, SettingsIntent, SettingsEvent> {

    private val _state = MutableStateFlow(SettingsState())
    override val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _eventChannel = Channel<SettingsEvent>()
    override val events: Flow<SettingsEvent> = _eventChannel.receiveAsFlow()

    init {
        scope.launch {
            settingsRepository.configuration.collect { persistedConfig ->
                _state.update { it.copy(configuration = persistedConfig) }
            }
        }
    }

    override fun dispatch(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SettingChanged -> handleSettingChanged(intent.newConfiguration)
            SettingsIntent.SaveChanges -> handleSaveChanges()
            is SettingsIntent.SavePluginSettings -> handleSavePluginSettings(intent)
            is SettingsIntent.SetActivePreset -> {
                _state.update {
                    it.copy(
                        configuration = it.configuration.copy(
                            activeServicePresetId = intent.presetId
                        )
                    )
                }
                dispatch(SettingsIntent.SaveChanges)
            }

            is SettingsIntent.UpdateServiceInActivePreset -> {
                val currentConfig = _state.value.configuration
                val activePresetId = currentConfig.activeServicePresetId ?: return

                val newPresets = currentConfig.servicePresets.map { preset ->
                    if (preset.id == activePresetId) {
                        val newSelectedServices = preset.selectedServices.toMutableMap()
                        newSelectedServices[intent.type] = intent.serviceId
                        preset.copy(selectedServices = newSelectedServices)
                    } else {
                        preset
                    }
                }

                _state.update {
                    it.copy(
                        configuration = currentConfig.copy(
                            servicePresets = newPresets
                        )
                    )
                }
                dispatch(SettingsIntent.SaveChanges)
            }
        }
    }

    private fun handleSettingChanged(newConfiguration: Configuration) {
        _state.update { it.copy(configuration = newConfiguration) }
    }

    private fun handleSaveChanges() {
        if (_state.value.isSaving) return
        scope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                settingsRepository.updateConfiguration(_state.value.configuration)
                _eventChannel.send(SettingsEvent.ShowMessage("Settings saved successfully", NotificationType.SUCCESS))
                _eventChannel.send(SettingsEvent.CloseSettingsDialog)
            } catch (e: Exception) {
                _eventChannel.send(SettingsEvent.ShowMessage("Error: Failed to save settings.", NotificationType.ERROR))
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun handleSavePluginSettings(intent: SettingsIntent.SavePluginSettings) {
        scope.launch {
            val result = pluginManager.applySettingsFromMap(intent.pluginId, intent.settings)
            result.fold(
                success = {
                    _eventChannel.send(SettingsEvent.ShowMessage("Plugin settings saved!", NotificationType.SUCCESS))
                },
                failure = { error ->
                    _eventChannel.send(SettingsEvent.ShowMessage("Error: ${error.message}", NotificationType.ERROR))
                }
            )
        }
    }
}
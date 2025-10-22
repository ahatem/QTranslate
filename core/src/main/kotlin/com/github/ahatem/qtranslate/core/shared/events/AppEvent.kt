package com.github.ahatem.qtranslate.core.shared.events

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Domain events that flow through the application.
 * 
 * Events represent "something happened" and allow decoupled communication
 * between different parts of the application without direct dependencies.
 */
sealed interface AppEvent {
    
    /**
     * Emitted when a service selection changes in the active preset.
     */
    data class ServiceSelectionChanged(
        val serviceType: ServiceType,
        val serviceId: String?
    ) : AppEvent
    
    /**
     * Emitted when the active preset changes.
     */
    data class ActivePresetChanged(
        val presetId: String,
        val presetName: String
    ) : AppEvent
    
    /**
     * Emitted when configuration is saved successfully.
     */
    data class ConfigurationSaved(
        val configuration: Configuration
    ) : AppEvent
    
    /**
     * Emitted when a plugin is successfully loaded.
     */
    data class PluginLoaded(
        val pluginId: String,
        val pluginName: String
    ) : AppEvent
    
    /**
     * Emitted when a plugin is unloaded.
     */
    data class PluginUnloaded(
        val pluginId: String
    ) : AppEvent
    
    /**
     * Emitted when theme changes.
     */
    data class ThemeChanged(
        val themeId: String
    ) : AppEvent
}

/**
 * Central event bus for application-wide event distribution.
 * 
 * This allows different parts of the application to communicate
 * without tight coupling. Stores can emit events that other stores
 * or UI components can observe.
 * 
 * Example usage:
 * ```
 * // In SettingsStore
 * eventBus.emit(AppEvent.ServiceSelectionChanged(type, serviceId))
 * 
 * // In MainStore
 * eventBus.events.collect { event ->
 *     when (event) {
 *         is AppEvent.ServiceSelectionChanged -> handleServiceChange(event)
 *     }
 * }
 * ```
 */
class AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    
    /**
     * Flow of all events emitted through the bus.
     * Multiple collectors can observe this flow independently.
     */
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()
    
    /**
     * Emit an event to all subscribers.
     * This is a non-blocking operation.
     */
    suspend fun emit(event: AppEvent) {
        _events.emit(event)
    }
    
    /**
     * Attempt to emit an event without suspending.
     * Returns true if successful, false if buffer is full.
     */
    fun tryEmit(event: AppEvent): Boolean {
        return _events.tryEmit(event)
    }
}
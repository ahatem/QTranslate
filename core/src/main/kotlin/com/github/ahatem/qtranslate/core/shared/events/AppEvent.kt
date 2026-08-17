package com.github.ahatem.qtranslate.core.shared.events

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Something that happened, announced without naming who should care.
 *
 * The stores are built independently and none of them holds a reference to another. An event is
 * how one of them says what it did without acquiring one.
 */
sealed interface AppEvent {

    data class ServiceSelectionChanged(
        val serviceType: ServiceRole,
        val serviceId: String?
    ) : AppEvent

    data class ActivePresetChanged(
        val presetId: String,
        val presetName: String
    ) : AppEvent

    data class ConfigurationSaved(
        val configuration: Configuration
    ) : AppEvent

    data class PluginLoaded(
        val pluginId: String,
        val pluginName: String
    ) : AppEvent

    data class PluginUnloaded(
        val pluginId: String
    ) : AppEvent

    data class ThemeChanged(
        val themeId: String
    ) : AppEvent
}

/**
 * Carries [AppEvent]s from whoever emitted one to whoever is collecting.
 *
 * No replay: a collector that subscribes late has missed whatever happened before it, which is
 * correct for events that describe a moment rather than a state. State belongs in the stores.
 */
class AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    suspend fun emit(event: AppEvent) {
        _events.emit(event)
    }

    /** Emits without suspending, dropping the event if the buffer is full. */
    fun tryEmit(event: AppEvent): Boolean {
        return _events.tryEmit(event)
    }
}

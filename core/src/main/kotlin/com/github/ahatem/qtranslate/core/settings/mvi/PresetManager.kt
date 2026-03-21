package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.*
import com.github.ahatem.qtranslate.core.shared.events.AppEvent
import com.github.ahatem.qtranslate.core.shared.events.AppEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

/**
 * Handles all service preset CRUD operations on behalf of [SettingsStore].
 *
 * This class is stateless — it never holds configuration itself. Instead, it
 * receives the current [Configuration] from the store, produces a new one,
 * and hands it back via [applyUpdate]. The store then decides how to persist it.
 *
 * Keeping preset logic here lets [SettingsStore] stay focused on the
 * save/cancel/draft lifecycle without growing unboundedly.
 *
 * @property applyUpdate Called with the new [Configuration] after each operation.
 *   The store uses this to update its working copy and trigger a save.
 * @property eventBus Used to emit domain events ([AppEvent.ActivePresetChanged],
 *   [AppEvent.ServiceSelectionChanged]) so other parts of the app can react.
 * @property eventChannel Used to send one-shot UI events (e.g. error messages).
 * @property scope Coroutine scope for launching event emissions.
 * @property logger Logger scoped to this component.
 */
internal class PresetManager(
    private val applyUpdate: (Configuration) -> Unit,
    private val eventBus: AppEventBus,
    private val eventChannel: SendChannel<SettingsEvent>,
    private val scope: CoroutineScope,
    private val logger: Logger
) {
    // -------------------------------------------------------------------------
    // Preset switching
    // -------------------------------------------------------------------------

    fun setActivePreset(current: Configuration, presetId: String) {
        val preset = current.servicePresets.find { it.id == presetId }
        if (preset == null) {
            logger.warn("Preset not found: $presetId — ignoring SetActivePreset intent")
            return
        }

        logger.info("Setting active preset: '$presetId' (${preset.name})")
        applyUpdate(current.withActivePresetId(presetId))

        scope.launch {
            eventBus.emit(AppEvent.ActivePresetChanged(presetId, preset.name))
        }
    }

    // -------------------------------------------------------------------------
    // Service selection
    // -------------------------------------------------------------------------

    fun updateServiceInActivePreset(
        current: Configuration,
        intent: SettingsIntent.UpdateServiceInActivePreset
    ) {
        if (current.activeServicePresetId == null) {
            logger.warn("No active preset — ignoring UpdateServiceInActivePreset intent")
            return
        }

        logger.info("Updating service: ${intent.type} → ${intent.serviceId ?: "none"}")
        applyUpdate(current.withServiceSelection(intent.type, intent.serviceId))

        scope.launch {
            eventBus.emit(AppEvent.ServiceSelectionChanged(intent.type, intent.serviceId))
        }
    }

    // -------------------------------------------------------------------------
    // Preset CRUD
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalUuidApi::class)
    fun createPreset(current: Configuration, name: String) {
        logger.info("Creating new preset: '$name'")
        val newPreset = ServicePreset.createDefault(name)
        val updated = current.withPreset(newPreset).withActivePresetId(newPreset.id)
        applyUpdate(updated)

        scope.launch {
            eventBus.emit(AppEvent.ActivePresetChanged(newPreset.id, newPreset.name))
        }
    }

    fun deletePreset(current: Configuration, presetId: String) {
        if (current.servicePresets.size <= 1) {
            logger.warn("Cannot delete last preset — ignoring DeletePreset intent")
            scope.launch {
                eventChannel.send(
                    SettingsEvent.ShowMessage("Cannot delete the last preset", NotificationType.ERROR)
                )
            }
            return
        }

        logger.info("Deleting preset: '$presetId'")
        val updated = current.withoutPreset(presetId)
        applyUpdate(updated)

        // If active preset changed, notify the rest of the app
        if (updated.activeServicePresetId != current.activeServicePresetId) {
            val newActive = updated.getActivePreset() ?: return
            scope.launch {
                eventBus.emit(AppEvent.ActivePresetChanged(newActive.id, newActive.name))
            }
        }
    }

    fun renamePreset(current: Configuration, presetId: String, newName: String) {
        logger.info("Renaming preset '$presetId' to '$newName'")
        applyUpdate(current.withPresetRenamed(presetId, newName))
    }
}
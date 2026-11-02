package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

/** Applies preset operations to the current draft. */
internal class PresetManager(
    private val applyUpdate: ((Configuration) -> Configuration) -> Unit,
    private val delete: (String) -> Boolean,
    private val eventChannel: SendChannel<SettingsEvent>,
    private val scope: CoroutineScope,
    private val logger: Logger
) {
    fun setActivePreset(id: String) = applyUpdate { current ->
        if (current.servicePresets.any { it.id == id }) current.withActivePresetId(id) else current
    }
    fun updateServiceInActivePreset(intent: SettingsIntent.UpdateServiceInActivePreset) = applyUpdate { current ->
        if (current.activeServicePresetId == null) current else current.withServiceSelection(intent.type, intent.serviceId)
    }
    fun addTranslator(intent: SettingsIntent.AddTranslatorToActivePreset) = applyUpdate { current ->
        if (current.activeServicePresetId == null) current else current.withTranslatorAdded(intent.serviceId)
    }
    fun removeTranslator(intent: SettingsIntent.RemoveTranslatorFromActivePreset) = applyUpdate { current ->
        if (current.activeServicePresetId == null) current else current.withTranslatorRemoved(intent.serviceId)
    }
    fun moveTranslator(intent: SettingsIntent.MoveTranslatorInActivePreset) = applyUpdate { current ->
        if (current.activeServicePresetId == null) current else current.withTranslatorMoved(intent.serviceId, intent.direction)
    }
    fun promoteTranslatorToPrimary(intent: SettingsIntent.PromoteTranslatorToPrimary) = applyUpdate { current ->
        if (current.activeServicePresetId == null) current
        else current.withActivePreset { it.withPromotedTranslator(intent.serviceId) }
    }
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun createPreset(name: String) {
        val preset = ServicePreset.createDefault(name)
        applyUpdate { it.withPreset(preset).withActivePresetId(preset.id) }
    }
    fun deletePreset(id: String) {
        val deleted = delete(id)
        if (!deleted) {
            logger.warn("Cannot delete last preset")
            scope.launch { eventChannel.send(SettingsEvent.ShowMessage("Cannot delete the last preset", NotificationType.ERROR)) }
        }
    }
    fun renamePreset(id: String, name: String) = applyUpdate { it.withPresetRenamed(id, name) }
}

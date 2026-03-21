package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.core.shared.arch.ServiceType

/**
 * Extension functions for working with [Configuration] immutably.
 *
 * All functions follow the same contract: they return a new [Configuration] with
 * the requested change applied, leaving the original unchanged. None of them
 * throw — invalid operations (e.g. activating a non-existent preset) return `this`
 * unchanged so callers don't need to guard against null results.
 */

/**
 * Returns a new configuration with the active preset transformed by [transform].
 * If there is no active preset, returns the configuration unchanged.
 *
 * Example:
 * ```kotlin
 * config.withActivePreset { it.copy(name = "Renamed") }
 * ```
 */
fun Configuration.withActivePreset(
    transform: (ServicePreset) -> ServicePreset
): Configuration {
    val activeId = activeServicePresetId ?: return this
    return copy(
        servicePresets = servicePresets.map { if (it.id == activeId) transform(it) else it }
    )
}

/**
 * Returns a new configuration with [serviceId] selected for [serviceType]
 * in the active preset. If there is no active preset, returns the configuration unchanged.
 *
 * Pass `null` for [serviceId] to clear the selection (fall back to first available).
 *
 * Example:
 * ```kotlin
 * config.withServiceSelection(ServiceType.TRANSLATOR, "google-translator")
 * ```
 */
fun Configuration.withServiceSelection(
    serviceType: ServiceType,
    serviceId: String?
): Configuration = withActivePreset { preset ->
    preset.copy(
        selectedServices = preset.selectedServices + (serviceType to serviceId)
    )
}

/**
 * Returns a new configuration with [presetId] as the active preset.
 * If [presetId] does not exist in [Configuration.servicePresets], returns the configuration unchanged.
 */
fun Configuration.withActivePresetId(presetId: String): Configuration =
    if (servicePresets.any { it.id == presetId }) copy(activeServicePresetId = presetId)
    else this

/**
 * Returns a new configuration with [preset] added to the presets list.
 * If a preset with the same ID already exists, it is replaced in-place.
 */
fun Configuration.withPreset(preset: ServicePreset): Configuration {
    val existingIndex = servicePresets.indexOfFirst { it.id == preset.id }
    val updated = if (existingIndex >= 0) {
        servicePresets.toMutableList().also { it[existingIndex] = preset }
    } else {
        servicePresets + preset
    }
    return copy(servicePresets = updated)
}

/**
 * Returns a new configuration with the preset identified by [presetId] removed.
 *
 * Rules:
 * - If [presetId] is the last preset, returns the configuration unchanged (cannot remove all presets).
 * - If the removed preset was active, the first remaining preset becomes active.
 */
fun Configuration.withoutPreset(presetId: String): Configuration {
    if (servicePresets.size <= 1) return this
    val remaining = servicePresets.filter { it.id != presetId }
    val newActiveId = if (activeServicePresetId == presetId) remaining.first().id
    else activeServicePresetId
    return copy(servicePresets = remaining, activeServicePresetId = newActiveId)
}

/**
 * Returns a new configuration with the preset identified by [presetId] renamed to [newName].
 * If [presetId] does not exist, returns the configuration unchanged.
 *
 * This correctly renames any preset — active or not — in a single pass.
 */
fun Configuration.withPresetRenamed(presetId: String, newName: String): Configuration =
    copy(
        servicePresets = servicePresets.map { preset ->
            if (preset.id == presetId) preset.copy(name = newName) else preset
        }
    )
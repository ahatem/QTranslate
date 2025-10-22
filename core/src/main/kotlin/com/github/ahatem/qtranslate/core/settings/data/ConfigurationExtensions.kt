package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.core.shared.arch.ServiceType

/**
 * Extension functions for working with Configuration immutably.
 *
 * These helpers make it easier to update nested configuration
 * without verbose copy operations.
 */

/**
 * Updates the active preset using the provided transform function.
 * If there's no active preset, returns the configuration unchanged.
 *
 * Example:
 * ```
 * config.withActivePreset { preset ->
 *     preset.copy(name = "New Name")
 * }
 * ```
 */
fun Configuration.withActivePreset(
    transform: (ServicePreset) -> ServicePreset
): Configuration {
    val activeId = this.activeServicePresetId ?: return this

    val updatedPresets = servicePresets.map { preset ->
        if (preset.id == activeId) transform(preset) else preset
    }

    return copy(servicePresets = updatedPresets)
}

/**
 * Updates a service selection in the active preset.
 *
 * This is a convenience method that combines withActivePreset with
 * service selection update logic.
 *
 * Example:
 * ```
 * config.withServiceSelection(ServiceType.TRANSLATOR, "google-translator")
 * ```
 */
fun Configuration.withServiceSelection(
    serviceType: ServiceType,
    serviceId: String?
): Configuration {
    return withActivePreset { preset ->
        preset.copy(
            selectedServices = preset.selectedServices.toMutableMap().apply {
                this[serviceType] = serviceId
            }
        )
    }
}

/**
 * Returns a new configuration with the specified preset as active.
 * If the preset ID doesn't exist, returns the configuration unchanged.
 */
fun Configuration.withActivePresetId(presetId: String): Configuration {
    val presetExists = servicePresets.any { it.id == presetId }
    return if (presetExists) {
        copy(activeServicePresetId = presetId)
    } else {
        this
    }
}

/**
 * Returns a new configuration with a preset added.
 * If a preset with the same ID already exists, replaces it.
 */
fun Configuration.withPreset(preset: ServicePreset): Configuration {
    val existingIndex = servicePresets.indexOfFirst { it.id == preset.id }

    val updatedPresets = if (existingIndex >= 0) {
        servicePresets.toMutableList().apply { set(existingIndex, preset) }
    } else {
        servicePresets + preset
    }

    return copy(servicePresets = updatedPresets)
}

/**
 * Returns a new configuration with the specified preset removed.
 * If this is the last preset, returns the configuration unchanged.
 * If the removed preset was active, activates the first remaining preset.
 */
fun Configuration.withoutPreset(presetId: String): Configuration {
    if (servicePresets.size <= 1) return this

    val filtered = servicePresets.filter { it.id != presetId }
    val newActiveId = if (activeServicePresetId == presetId) {
        filtered.firstOrNull()?.id
    } else {
        activeServicePresetId
    }

    return copy(
        servicePresets = filtered,
        activeServicePresetId = newActiveId
    )
}

/**
 * Returns a new configuration with a preset renamed.
 */
fun Configuration.withPresetRenamed(presetId: String, newName: String): Configuration {
    return withActivePreset { preset ->
        if (preset.id == presetId) {
            preset.copy(name = newName)
        } else {
            preset
        }
    }.let { config ->
        // If active preset wasn't the one renamed, manually update it
        config.copy(
            servicePresets = config.servicePresets.map { preset ->
                if (preset.id == presetId) preset.copy(name = newName) else preset
            }
        )
    }
}
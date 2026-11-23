package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresetKind
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresets
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope

internal object HotkeyDraftOperations {

    fun replaceBinding(configuration: Configuration, binding: HotkeyBinding): Configuration {
        // Post-v7 rows must never leak into the legacy `hotkeys` list, so a downgrade
        // never sees an unknown enum value there. OCR rows → ocrHotkeys, writing rows →
        // writingHotkeys, everything else → legacy hotkeys.
        return if (binding.action in HotkeyBinding.OCR_ACTIONS) {
            configuration.copy(
                ocrHotkeys = configuration.ocrHotkeys
                    .filterNot { it.action == binding.action } + binding
            )
        } else if (binding.action in HotkeyBinding.WRITING_ACTIONS) {
            configuration.copy(
                writingHotkeys = configuration.writingHotkeys
                    .filterNot { it.action == binding.action } + binding
            )
        } else {
            configuration.copy(
                hotkeys = configuration.hotkeys
                    .filterNot { it.action == binding.action } + binding
            )
        }
    }

    fun replacePreset(configuration: Configuration, preset: HotkeyPresetKind): Configuration =
        when (preset) {
            HotkeyPresetKind.LEGACY -> configuration.copy(hotkeys = HotkeyPresets.LEGACY.map { it.copy() })
            HotkeyPresetKind.MODERN -> configuration.copy(hotkeys = HotkeyPresets.MODERN.map { it.copy() })
            HotkeyPresetKind.CUSTOM -> clearForCustom(configuration)
        }

    fun clearForCustom(configuration: Configuration): Configuration = configuration.copy(
        hotkeys = configuration.hotkeys.map {
            it.copy(keyCode = 0, modifiers = 0, isDoubleCtrlEnabled = false)
        },
        writingHotkeys = configuration.writingHotkeys.map {
            it.copy(keyCode = 0, modifiers = 0, isDoubleCtrlEnabled = false)
        },
    )

    /**
     * Restores every binding group the panel shows.
     *
     * "Reset All to Defaults" must cover OCR and writing shortcuts too, not only the legacy list.
     * [HotkeyPresets] has no opinion about those groups; their defaults live on [HotkeyBinding],
     * and this is where all three meet.
     */
    fun resetAllBindings(configuration: Configuration): Configuration = configuration.copy(
        hotkeys = HotkeyPresets.LEGACY.map { it.copy() },
        ocrHotkeys = HotkeyBinding.OCR_DEFAULTS.map { it.copy() },
        writingHotkeys = HotkeyBinding.WRITING_DEFAULTS.map { it.copy() },
    )

    fun clearShowMainWindow(configuration: Configuration): Configuration {
        val current = binding(configuration, HotkeyAction.SHOW_MAIN_WINDOW)
            ?: HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW, scope = HotkeyScope.GLOBAL)
        return replaceBinding(
            configuration,
            current.copy(keyCode = 0, modifiers = 0, scope = HotkeyScope.GLOBAL)
        )
    }

    fun setDoubleCtrl(configuration: Configuration, enabled: Boolean): Configuration {
        val current = binding(configuration, HotkeyAction.SHOW_MAIN_WINDOW)
            ?: HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW, scope = HotkeyScope.GLOBAL)
        return replaceBinding(
            configuration,
            current.copy(isDoubleCtrlEnabled = enabled, scope = HotkeyScope.GLOBAL)
        )
    }

    fun toggleScope(configuration: Configuration, action: HotkeyAction): Configuration {
        val current = binding(configuration, action) ?: return configuration
        if (action == HotkeyAction.SHOW_MAIN_WINDOW) return configuration
        // Capture actions belong to the overlay. There is no meaningful global/local variant.
        if (current.scope == HotkeyScope.CAPTURE) return configuration
        return replaceBinding(
            configuration,
            current.copy(
                scope = if (current.scope == HotkeyScope.GLOBAL) HotkeyScope.LOCAL else HotkeyScope.GLOBAL
            )
        )
    }

    private fun binding(configuration: Configuration, action: HotkeyAction): HotkeyBinding? =
        configuration.effectiveHotkeys.find { it.action == action }
}

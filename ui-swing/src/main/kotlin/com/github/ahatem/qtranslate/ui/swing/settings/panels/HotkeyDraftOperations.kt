package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresetKind
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresets
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope

/** Pure transformations used by the Keyboard settings page. */
internal object HotkeyDraftOperations {

    fun replaceBinding(configuration: Configuration, binding: HotkeyBinding): Configuration {
        val bindings = configuration.hotkeys
            .filterNot { it.action == binding.action } + binding
        return configuration.copy(hotkeys = bindings)
    }

    fun replacePreset(configuration: Configuration, preset: HotkeyPresetKind): Configuration =
        when (preset) {
            HotkeyPresetKind.LEGACY -> configuration.copy(hotkeys = HotkeyPresets.LEGACY.map { it.copy() })
            HotkeyPresetKind.MODERN -> configuration.copy(hotkeys = HotkeyPresets.MODERN.map { it.copy() })
            HotkeyPresetKind.CUSTOM -> clearForCustom(configuration)
        }

    fun clearForCustom(configuration: Configuration): Configuration = configuration.copy(
        hotkeys = configuration.hotkeys.map { binding ->
            binding.copy(
                keyCode = 0,
                modifiers = 0,
                isDoubleCtrlEnabled = false
            )
        }
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
        return replaceBinding(
            configuration,
            current.copy(
                scope = if (current.scope == HotkeyScope.GLOBAL) HotkeyScope.LOCAL else HotkeyScope.GLOBAL
            )
        )
    }

    private fun binding(configuration: Configuration, action: HotkeyAction): HotkeyBinding? =
        configuration.hotkeys.find { it.action == action }
}

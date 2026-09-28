package com.github.ahatem.qtranslate.core.settings.data

import kotlin.test.Test
import kotlin.test.assertTrue

class HotkeyPresetsTest {

    /**
     * Two different actions sharing one accelerator is a real, observable bug: whichever one
     * dispatches first wins, and depending on GLOBAL/LOCAL scope the other may appear to simply
     * not work while the app is focused. This must hold for every built-in preset, not just the
     * ones a human happened to notice.
     */
    @Test
    fun `no built-in preset binds two actions to the same accelerator`() {
        for (preset in listOf(
            "LEGACY" to HotkeyPresets.LEGACY,
            "MODERN" to HotkeyPresets.MODERN
        )) {
            val (name, bindings) = preset
            val collisions = bindings
                .filter { it.hasBinding }
                .groupBy { it.keyCode to it.modifiers }
                .filterValues { it.size > 1 }

            assertTrue(
                collisions.isEmpty(),
                "$name preset has colliding accelerators: " + collisions.entries.joinToString { (accelerator, bindingsForKey) ->
                    val (keyCode, modifiers) = accelerator
                    val actions = bindingsForKey.joinToString(", ") { "${it.action} (${it.scope})" }
                    "keyCode=$keyCode modifiers=$modifiers -> $actions"
                }
            )
        }
    }

    @Test
    fun `every hotkey action has exactly one binding per preset`() {
        for (preset in listOf(HotkeyPresets.LEGACY, HotkeyPresets.MODERN)) {
            val actionCounts = preset.groupingBy { it.action }.eachCount()
            assertTrue(
                actionCounts.values.all { it == 1 },
                "Expected exactly one binding per action, got: $actionCounts"
            )
        }
    }

    @Test
    fun `presets are identified correctly and diverge into custom`() {
        kotlin.test.assertEquals(HotkeyPresetKind.LEGACY, HotkeyPresets.identify(HotkeyPresets.LEGACY))
        kotlin.test.assertEquals(HotkeyPresetKind.MODERN, HotkeyPresets.identify(HotkeyPresets.MODERN))

        val customized = HotkeyPresets.LEGACY.map {
            if (it.action == HotkeyAction.SHOW_QUICK_TRANSLATE) it.copy(keyCode = 999) else it
        }
        kotlin.test.assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(customized))
    }
}

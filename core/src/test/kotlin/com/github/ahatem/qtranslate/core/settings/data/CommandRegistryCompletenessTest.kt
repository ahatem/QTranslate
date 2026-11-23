package com.github.ahatem.qtranslate.core.settings.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Registry invariants: one spec per [HotkeyAction], defaults matching the spec, and surfaces and
 * scopes agreeing with [HotkeyBinding.DEFAULTS].
 */
class CommandRegistryCompletenessTest {

    @Test
    fun `every hotkey action has exactly one command spec`() {
        val specActions = CommandRegistry.SPECS.map { it.action }
        assertEquals(HotkeyAction.entries.toSet(), specActions.toSet(), "spec coverage")
        assertEquals(specActions.size, specActions.toSet().size, "no duplicate specs")
        assertEquals(
            HotkeyAction.entries.toSet(),
            HotkeyBinding.DEFAULTS.map { it.action }.toSet(),
            "DEFAULTS coverage",
        )
        HotkeyAction.entries.forEach { action ->
            assertNotNull(CommandRegistry.specFor(action), "$action needs a spec")
        }
    }

    @Test
    fun `every spec default binding matches the DEFAULTS row for its action`() {
        val defaultsByAction = HotkeyBinding.DEFAULTS.associateBy { it.action }
        CommandRegistry.SPECS.forEach { spec ->
            assertEquals(
                defaultsByAction[spec.action],
                spec.defaultBinding,
                "default binding for ${spec.action}",
            )
        }
    }

    @Test
    fun `specs for a surface are exactly the specs that surface-dispatched`() {
        val defaultsByScope = HotkeyBinding.DEFAULTS.groupBy { it.scope }
        assertEquals(
            defaultsByScope[HotkeyScope.GLOBAL]?.map { it.action }?.toSet(),
            CommandRegistry.specsForSurface(CommandSurface.GLOBAL).map { it.action }.toSet(),
        )
        assertEquals(
            defaultsByScope[HotkeyScope.LOCAL]?.map { it.action }?.toSet(),
            CommandRegistry.specsForSurface(CommandSurface.MAIN_WINDOW).map { it.action }.toSet(),
        )
        assertEquals(
            defaultsByScope[HotkeyScope.CAPTURE]?.map { it.action }?.toSet(),
            CommandRegistry.specsForSurface(CommandSurface.CAPTURE_OVERLAY).map { it.action }.toSet(),
        )
    }

    @Test
    fun `eligible scopes contain the default scope and nothing outside the known vocabulary`() {
        CommandRegistry.SPECS.forEach { spec ->
            assertTrue(spec.defaultScope in spec.eligibleScopes, "${spec.action} default scope not eligible")
            assertTrue(
                spec.eligibleScopes.all { it in HotkeyScope.entries },
                "${spec.action} reaches outside the scope vocabulary",
            )
        }
    }
}

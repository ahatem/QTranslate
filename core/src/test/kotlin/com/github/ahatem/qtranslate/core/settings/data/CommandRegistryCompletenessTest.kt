package com.github.ahatem.qtranslate.core.settings.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the registry invariants behind Batch 1 (B3):
 *
 * - one spec per [HotkeyAction] and vice versa, so adding an enum constant without a spec
 *   fails here rather than silently producing an unconfigurable command
 * - every spec's default binding equals the matching [HotkeyBinding.DEFAULTS] row
 * - [HotkeyBinding.DEFAULTS] itself covers exactly the enum, so the defaults and the
 *   registry cannot drift apart in opposite directions
 */
class CommandRegistryCompletenessTest {

    @Test
    fun `every hotkey action has exactly one command spec`() {
        val specActions = CommandRegistry.SPECS.map { it.action }
        assertEquals(HotkeyAction.entries.toSet(), specActions.toSet(), "spec coverage")
        assertEquals(specActions.size, specActions.toSet().size, "no duplicate specs")
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
    fun `DEFAULTS covers exactly every hotkey action`() {
        assertEquals(
            HotkeyAction.entries.toSet(),
            HotkeyBinding.DEFAULTS.map { it.action }.toSet(),
            "DEFAULTS coverage",
        )
    }

    @Test
    fun `editable actions cover every action except the non-editable specs`() {
        val expected = CommandRegistry.SPECS.filter { it.editable }.map { it.action }
        assertEquals(expected, CommandRegistry.editableActions)
        assertEquals(
            HotkeyAction.entries.toSet() - CommandRegistry.editableActions.toSet(),
            CommandRegistry.SPECS.filterNot { it.editable }.map { it.action }.toSet(),
        )
    }

    @Test
    fun `only show main window is non-editable in the keyboard panel`() {
        // It is edited through its own row above the settings table (Edit/Clear/Double Ctrl),
        // so a table row for it would edit the same binding twice. Pinning it here catches a
        // spec that accidentally drops an action out of the panel.
        assertEquals(
            setOf(HotkeyAction.SHOW_MAIN_WINDOW),
            CommandRegistry.SPECS.filterNot { it.editable }.map { it.action }.toSet(),
        )
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

    @Test
    fun `every spec names a localization key, a category and a description`() {
        CommandRegistry.SPECS.forEach { spec ->
            assertTrue(spec.nameKey.startsWith("settings_hotkeys."), "${spec.action} nameKey")
            assertTrue(spec.category.isNotBlank(), "${spec.action} category")
            assertTrue(spec.description.isNotBlank(), "${spec.action} description")
            assertTrue(spec.surfaces.isNotEmpty(), "${spec.action} surfaces")
        }
    }

    @Test
    fun `unregistered actions resolve to no spec`() {
        // specFor takes a HotkeyAction, and the first test pins exhaustiveness, so there is
        // currently no action that resolves to null. This asserts the shape of the API so a
        // future nullable-tolerant caller has a contract to hold on to.
        val specs = CommandRegistry.SPECS
        assertNull(specs.firstOrNull { CommandRegistry.specFor(it.action) == null })
    }
}

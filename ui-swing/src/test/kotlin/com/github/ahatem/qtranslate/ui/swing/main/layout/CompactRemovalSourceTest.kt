package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Compact layout and everything that only existed to serve its tabs are gone from production
 * code. Read from the sources because the point is what is no longer there.
 */
class CompactRemovalSourceTest {
    private val roots = listOf(
        File("src/main/kotlin"),
        File("../app/src/main/kotlin"),
        File("../core/src/main/kotlin"),
    )

    private fun productionSources() = roots.flatMap { root ->
        root.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private val removedNames = listOf(
        "CompactLayout",
        "LayoutType.COMPACT",
        "LayoutPresetIds.COMPACT",
        "WithTabs",
        "compactStrokes",
        "updateCompactShortcuts",
        "selectCompactTab",
        "ensureCompactTabVisible",
        "compact-focus-",
        "layout_preset_compact",
        "layout_compact",
        "switchToAndFocus",
    )

    @Test
    fun `the source roots are found`() {
        assertTrue(productionSources().size > 100, "the scan must actually read the production sources")
    }

    @Test
    fun `no compact layout plumbing remains in production code`() {
        val offenders = productionSources().flatMap { file ->
            val text = file.readText()
            removedNames.filter { it in text }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `the compact layout id survives only as the legacy id that resolves to Classic`() {
        val quoted = Regex(""""compact"""")
        val offenders = productionSources()
            .filter { file ->
                file.readLines().any { line -> !line.trim().startsWith("//") && quoted.containsMatchIn(line) }
            }
            .map { it.name }
        assertEquals(listOf("Configuration.kt"), offenders)
    }

    @Test
    fun `the layout code has no tab component`() {
        val layoutSources = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/layout")
            .walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(layoutSources.isNotEmpty())
        layoutSources.forEach { assertFalse("JTabbedPane" in it.readText(), "${it.name} must not build tabs") }
    }

    @Test
    fun `responsive presentation never touches the saved layout`() {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/layout/ResponsivePairSplit.kt")
            .readText()
        listOf("Configuration", "SettingsIntent", "SettingsStore", "layoutPresetId", "LayoutPresetIds").forEach {
            assertFalse(it in source, "the responsive pair must not reference $it")
        }
    }
}

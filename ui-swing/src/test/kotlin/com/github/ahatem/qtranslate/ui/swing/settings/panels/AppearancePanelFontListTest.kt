package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.fonts.inter.FlatInterFont
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Appearance font pickers merge system families with every bundled family registered for lazy
 * loading (Inter, Rubik, Noto Naskh Arabic), and the fallback picker adds one more entry, "Automatic
 * (Recommended)", ahead of them. None of that may show up twice, and a fresh default configuration
 * must land on Inter and Automatic — see P10-C2.
 */
class AppearancePanelFontListTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-appearance-test").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-appearance-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(Configuration.DEFAULT)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, Configuration.DEFAULT)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun panel(): AppearancePanel {
        // Registered the same way AppUiSetup registers them at startup, so the picker's merged
        // enumeration includes them exactly as it would at runtime.
        FlatInterFont.installLazy()
        RubikSansFont.installLazy()
        NotoNaskhArabicFont.installLazy()

        val settings = store()
        val themeManager = ThemeManager(Files.createTempDirectory("qtranslate-appearance-themes").toFile(), logger)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        val panel = onEdt { AppearancePanel(settings, themeManager, localizer) }
        awaitLoaded(panel)
        return panel
    }

    /**
     * The font lists load on a [javax.swing.SwingWorker]; this waits for that pass to finish.
     *
     * The first pass enumerates every installed font. On a machine with several hundred families
     * that takes about six seconds when idle and has taken over thirty inside the full suite, so
     * the deadline is generous. It only bounds how long a hang can take to report; the assertions
     * are unchanged.
     */
    private fun awaitLoaded(panel: AppearancePanel, timeoutMs: Long = 120_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onEdt { panel.fallbackFontComboForTest().isEnabled }) return
            Thread.sleep(20)
        }
        error("Appearance panel's font lists never finished loading")
    }

    @Test
    fun `font pickers list each family once and offer the bundled fonts`() {
        val p = panel()
        fun items(combo: javax.swing.JComboBox<*>) =
            onEdt { (0 until combo.itemCount).map { combo.getItemAt(it).toString() } }

        val automatic = localizer.getString("settings_appearance.automatic_fallback")
        val fallback = items(p.fallbackFontComboForTest())
        assertEquals(1, fallback.count { it == automatic }, "expected exactly one '$automatic' entry, found: $fallback")

        listOf(p.uiFontComboForTest(), p.editorFontComboForTest()).forEach { combo ->
            val items = items(combo)

            // The regression this guards is a family being listed more than once, which is what
            // happened when the list was built by compounding a font that had already been
            // derived.
            assertEquals(items.size, items.distinct().size, "no family is listed twice, found: $items")

            // Presence is checked against the family's name and its style variants: once the JDK
            // has constructed a bundled font it may report "Inter Light" and "Inter SemiBold" as
            // families of their own instead of a single "Inter", depending on test order.
            listOf("Inter", "Rubik", "Noto Naskh Arabic").forEach { family ->
                assertTrue(
                    items.any { it == family || it.startsWith("$family ") },
                    "$family should be offered, found: ${items.filter { it.startsWith(family.split(" ").first()) }}",
                )
            }
        }
    }
}

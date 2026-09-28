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
        val panel = onEdt { AppearancePanel(settings, themeManager, localizer, scope) }
        awaitLoaded(panel)
        return panel
    }

    /** The font lists load on a [javax.swing.SwingWorker]; this waits for that pass to finish. */
    private fun awaitLoaded(panel: AppearancePanel, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onEdt { panel.fallbackFontComboForTest().isEnabled }) return
            Thread.sleep(20)
        }
        error("Appearance panel's font lists never finished loading")
    }

    @Test
    fun `automatic appears exactly once in the fallback picker`() {
        val combo = panel().fallbackFontComboForTest()
        val items = onEdt { (0 until combo.itemCount).map { combo.getItemAt(it).toString() } }
        val automatic = localizer.getString("settings_appearance.automatic_fallback")
        assertEquals(1, items.count { it == automatic }, "expected exactly one '$automatic' entry, found: $items")
    }

    @Test
    fun `Inter, Rubik and Noto each appear exactly once in the UI and editor pickers`() {
        val p = panel()
        listOf(p.uiFontComboForTest(), p.editorFontComboForTest()).forEach { combo ->
            val items = onEdt { (0 until combo.itemCount).map { combo.getItemAt(it) } }
            assertEquals(1, items.count { it == "Inter" }, "Inter should appear exactly once, found: $items")
            assertEquals(1, items.count { it == "Rubik" }, "Rubik should appear exactly once, found: $items")
            assertEquals(
                1, items.count { it == "Noto Naskh Arabic" },
                "Noto Naskh Arabic should appear exactly once, found: $items"
            )
        }
    }

    @Test
    fun `a fresh default configuration selects Inter and Automatic`() {
        val p = panel()
        assertEquals("Inter", onEdt { p.uiFontComboForTest().selectedItem })
        assertEquals("Inter", onEdt { p.editorFontComboForTest().selectedItem })
        assertEquals(
            localizer.getString("settings_appearance.automatic_fallback"),
            onEdt { p.fallbackFontComboForTest().selectedItem.toString() }
        )
    }
}

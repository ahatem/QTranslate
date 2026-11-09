package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The language bar's Translate button must not be actionable on a guaranteed no-op
 * (blank input), while Cancel must stay actionable for the whole loading window
 * so a running translation can always be stopped.
 */
class LanguageSelectionBarTranslateButtonTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-language-bar-test").toFile(), LanguageTomlParser(), logger)
    }

    /**
     * A real IconManager around an unconstructed PluginManager: the app-icon
     * lookup used here never touches the plugin manager, so no plugin graph is
     * needed, and the icon cache is initialized by the constructor.
     */
    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return IconManager(allocate.invoke(theUnsafe, com.github.ahatem.qtranslate.core.plugin.PluginManager::class.java) as com.github.ahatem.qtranslate.core.plugin.PluginManager)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun bar(onCancel: () -> Unit = {}) = onEdt {
        LanguageSelectionBar(
            iconManager = blindIconManager(),
            localizer = localizer,
            onClear = {},
            onSourceLanguageSelected = {},
            onSwap = {},
            onTargetLanguageSelected = {},
            onTranslate = {},
            onCancel = onCancel,
        )
    }

    private fun state(isLoading: Boolean, canTranslate: Boolean) = LanguageSelectionBarState(
        isLoading = isLoading,
        canClear = canTranslate,
        canSwap = true,
        canTranslate = canTranslate,
        allSourceLanguages = listOf(LanguageCode.ENGLISH),
        allTargetLanguages = listOf(LanguageCode.GERMAN),
        selectedSourceLanguage = LanguageCode.ENGLISH,
        selectedTargetLanguage = LanguageCode.GERMAN,
        strings = LanguageSelectionBarStrings(
            translateButtonText = "Translate",
            cancelButtonText = "Cancel",
            clearTooltip = "Clear",
            swapTooltip = "Swap",
        ),
    )

    private fun translateButtonOf(bar: LanguageSelectionBar): JButton = onEdt {
        val found = mutableListOf<JButton>()
        fun walk(c: Component) {
            if (c is JButton) found += c
            if (c is Container) c.components.forEach(::walk)
        }
        walk(bar)
        found.single { it.text == "Translate" || it.text == "Cancel" }
    }

    @Test
    fun `translate is disabled when there is nothing to translate`() {
        val bar = bar()
        onEdt { bar.render(state(isLoading = false, canTranslate = false)) }

        val button = translateButtonOf(bar)
        assertEquals("Translate", button.text)
        assertFalse(button.isEnabled, "blank input is a guaranteed no-op: Translate must not be actionable")
    }

    @Test
    fun `translate is enabled when there is input to translate`() {
        val bar = bar()
        onEdt { bar.render(state(isLoading = false, canTranslate = true)) }

        val button = translateButtonOf(bar)
        assertEquals("Translate", button.text)
        assertTrue(button.isEnabled)
    }

    @Test
    fun `cancel stays actionable while loading even when canTranslate is false`() {
        var cancelled = 0
        val bar = bar(onCancel = { cancelled++ })
        onEdt { bar.render(state(isLoading = true, canTranslate = false)) }

        val button = translateButtonOf(bar)
        assertEquals("Cancel", button.text)
        assertTrue(button.isEnabled, "Cancel must stay actionable while a translation is running")
        onEdt { button.doClick() }
        assertEquals(1, cancelled)
    }
}

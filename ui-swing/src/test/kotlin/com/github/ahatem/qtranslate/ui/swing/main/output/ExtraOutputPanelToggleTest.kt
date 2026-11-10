package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-panel Extra Output toggle buttons route every selection change to
 * [ExtraOutputState.onTypeChanged], and a re-render reflects the new active type
 * in the button group — the panel half of the persistence contract. What the
 * callback does with the type (persist immediately vs. draft-only) is owned by
 * the wiring in MainContentView.
 */
class ExtraOutputPanelToggleTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-extra-toggle-test").toFile(), LanguageTomlParser(), logger)
    }

    private val textFont = FontConfig("Dialog", 14)

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

    private fun panel(onTypeChanged: (ExtraOutputType) -> Unit) = onEdt {
        ExtraOutputPanel(
            blindIconManager(), localizer,
            onListen = {}, onTranslateRequest = {},
        ).also { panel ->
            panel.render(
                ExtraOutputState(
                    text = "translated text",
                    fontConfig = textFont,
                    fallbackFontConfig = textFont,
                    isLoading = false,
                    isVisible = true,
                    actionsState = TextActionsState(emptyList()),
                    activeType = ExtraOutputType.BackwardTranslate,
                    labelBackward = "Backward",
                    labelSummary = "Summary",
                    labelRewrite = "Rewrite",
                    onTypeChanged = onTypeChanged,
                )
            )
        }
    }

    private fun togglesOf(panel: ExtraOutputPanel): Map<String, JToggleButton> = onEdt {
        val found = mutableMapOf<String, JToggleButton>()
        fun walk(c: Component) {
            if (c is JToggleButton) found[c.text] = c
            if (c is Container) c.components.forEach(::walk)
        }
        walk(panel)
        found
    }

    @Test
    fun `clicking a toggle reports the new type once`() {
        val changes = mutableListOf<ExtraOutputType>()
        val toggles = togglesOf(panel { changes += it })

        onEdt { toggles.getValue("Summary").doClick() }

        assertEquals(listOf(ExtraOutputType.Summarize), changes)
    }

    @Test
    fun `render reflects the active type across re-renders`() {
        val panel = panel {}
        val toggles = togglesOf(panel)

        assertTrue(onEdt { toggles.getValue("Backward").isSelected }, "initial render must select the active type")

        onEdt { toggles.getValue("Rewrite").doClick() }
        assertTrue(onEdt { toggles.getValue("Rewrite").isSelected })
        assertTrue(onEdt { !toggles.getValue("Backward").isSelected })

        // A later render with the new active type keeps the selection where the user put it.
        onEdt {
            panel.render(
                ExtraOutputState(
                    text = "translated text",
                    fontConfig = textFont,
                    fallbackFontConfig = textFont,
                    isLoading = false,
                    isVisible = true,
                    actionsState = TextActionsState(emptyList()),
                    activeType = ExtraOutputType.Rewrite,
                    labelBackward = "Backward",
                    labelSummary = "Summary",
                    labelRewrite = "Rewrite",
                )
            )
        }
        val after = togglesOf(panel)
        assertTrue(onEdt { after.getValue("Rewrite").isSelected })
        assertTrue(onEdt { !after.getValue("Backward").isSelected })
    }
}

package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.input.InputTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.input.InputTextState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import javax.swing.JComponent
import javax.swing.JTextPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the main window is shown, keyboard focus must land in the input editor.
 *
 * The bug this pins: focus used to be requested on the input *panel* instead of the editor. A
 * JPanel accepts focus, so the request did not fail, it landed on the container, where typing
 * goes nowhere. The order of the show sequence is covered by [MainWindowPresentationTest].
 */
class MainWindowFocusOnShowTest {

    private val testLocalizer by lazy {
        val dir = java.nio.file.Files.createTempDirectory("qtranslate-focus-test").toFile()
        LocalizationManager(dir, LanguageTomlParser(), SilentLogger)
    }

    /**
     * IconManager's only constructor needs a full PluginManager graph, but
     * panel construction never calls into it — icons resolve lazily at render
     * from [com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel].
     * Allocate without running the constructor; any future call into it would
     * fail loudly here instead of silently changing test meaning.
     */
    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private fun newInputPanel(): InputTextPanel = onEdt {
        InputTextPanel(
            iconManager = blindIconManager(),
            localizationManager = testLocalizer,
            onTextChanged = {},
            onListen = {},
            onTranslateRequest = {},
            onCorrectionApplied = { _, _ -> },
        )
    }

    private fun renderText(panel: InputTextPanel, text: String) {
        val font = FontConfig("SansSerif", 14)
        panel.render(
            InputTextState(
                text = text,
                corrections = emptyList(),
                fontConfig = font,
                fallbackFontConfig = font,
                isEditable = true,
                isLoading = false,
                actionsState = TextActionsState(emptyList()),
            )
        )
    }

    // -----------------------------------------------------------------------
    // Focus-seam contract on real components
    // -----------------------------------------------------------------------

    @Test
    fun `input editor is the only viable focus target inside its panel`() {
        val panel = newInputPanel()

        assertTrue(
            panel.textPaneComponent.isFocusable,
            "the input editor must accept focus — it is the only valid focus target",
        )
        assertTrue(
            panel.textPaneComponent.isRequestFocusEnabled,
            "the input editor must have focus requests enabled",
        )
        assertFalse(
            panel.textPaneComponent === (panel as JComponent),
            "the focus target must be the inner editor, never the outer container: " +
                "focus landing on the container leaves typing going nowhere",
        )
    }

    @Test
    fun `input focus seam preserves existing text across repeated shows`() {
        val panel = newInputPanel()
        onEdt {
            renderText(panel, "hello world")
            panel.requestFocusOnText()
            panel.requestFocusOnText()
        }

        assertEquals(
            "hello world",
            onEdt { (panel.textPaneComponent as JTextPane).text },
            "requesting input focus must not clear or mutate the editor text",
        )
    }
}

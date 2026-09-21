package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptionsPopupMenuTest {

    @Test
    fun `menu groups main actions in the expected order`() {
        val menu = createMenu()

        assertEquals(
            listOf(
                "Spell Checking",
                "Instant Translation",
                "Extra Output",
                "Options",
                null,
                "Dictionary",
                "Image Search",
                "Recognize Text (OCR)",
                "Translate Document...",
                "History",
                null,
                "Settings...",
                "Help",
                null,
                "Exit",
            ),
            menu.components.map { component ->
                when (component) {
                    is JMenuItem -> component.text
                    is JSeparator -> null
                    else -> error("Unexpected menu component: ${component::class.java.name}")
                }
            },
        )
    }

    @Test
    fun `extra output uses one radio item for every configured type`() {
        val selected = mutableListOf<ExtraOutputType>()
        val menu = createMenu(
            config = Configuration.DEFAULT.copy(extraOutputType = ExtraOutputType.Summarize),
            actions = createActions(onChangeExtraOutput = { selected += it }),
        )
        val extraOutput = menu.components.filterIsInstance<JMenu>().single { it.text == "Extra Output" }
        val items = extraOutput.menuComponents.filterIsInstance<JRadioButtonMenuItem>()

        assertEquals(listOf("None", "Backward Translation", "Summarization", "Rewriting"), items.map { it.text })
        assertEquals(1, items.count { it.isSelected })
        assertTrue(items[2].isSelected)

        val expected = listOf(
            ExtraOutputType.None,
            ExtraOutputType.BackwardTranslate,
            ExtraOutputType.Summarize,
            ExtraOutputType.Rewrite,
        )
        expected.forEachIndexed { index, type ->
            items[index].doClick()
            assertEquals(type, selected.last())
            assertEquals(1, items.count { it.isSelected })
            assertTrue(items[index].isSelected)
        }
    }

    @Test
    fun `layout presets use one radio item for every available layout`() {
        val selected = mutableListOf<String>()
        val menu = createMenu(
            config = Configuration.DEFAULT.copy(layoutPresetId = "side_by_side"),
            actions = createActions(onChangeLayoutPreset = { selected += it }),
        )
        val view = menu.components.filterIsInstance<JMenu>().single { it.text == "Options" }
        val presets = view.menuComponents.filterIsInstance<JMenu>().single { it.text == "Layout Presets" }
        val items = presets.menuComponents.filterIsInstance<JRadioButtonMenuItem>()

        assertEquals(listOf("Classic", "Side By Side", "Compact"), items.map { it.text })
        assertEquals(1, items.count { it.isSelected })
        assertTrue(items[1].isSelected)

        items[0].doClick()
        assertEquals(listOf("classic"), selected)
        assertEquals(1, items.count { it.isSelected })
        assertTrue(items[0].isSelected)
    }

    @Test
    fun `dictionary remains an inline panel toggle and tools invoke their callbacks`() {
        val calls = mutableListOf<String>()
        val menu = createMenu(
            actions = createActions(
                onShowDictionary = { calls += "dictionary" },
                onShowImageSearch = { calls += "image" },
                onRecognizeText = { calls += "ocr" },
                onTranslateDocument = { calls += "document" },
                onShowHistory = { calls += "history" },
                onShowSettings = { calls += "settings" },
                onExitApplication = { calls += "exit" },
            ),
        )

        val dictionary = menu.components.filterIsInstance<JCheckBoxMenuItem>().single { it.text == "Dictionary" }
        assertFalse(dictionary.isSelected)
        dictionary.doClick()
        menu.item("Image Search").doClick()
        menu.item("Recognize Text (OCR)").doClick()
        menu.item("Translate Document...").doClick()
        menu.item("History").doClick()
        menu.item("Settings...").doClick()
        menu.item("Exit").doClick()

        assertEquals(listOf("dictionary", "image", "ocr", "document", "history", "settings", "exit"), calls)
    }

    @Test
    fun `help is organized by task and keeps callbacks wired`() {
        val calls = mutableListOf<String>()
        val menu = createMenu(
            config = Configuration.DEFAULT.copy(autoCheckForUpdates = true),
            actions = createActions(
                onShowHowToUse = { calls += "how" },
                onContactUs = { calls += "contact" },
                onCheckForUpdates = { calls += "check" },
                onToggleAutoCheckForUpdates = { calls += "auto:$it" },
                onShowAboutQTranslate = { calls += "about" },
            ),
        )
        val help = menu.components.filterIsInstance<JMenu>().single { it.text == "Help" }

        assertEquals(
            listOf("How to Use", "Contact Us", null, "Check for Updates", "Auto-check for Updates", null, "About QTranslate"),
            help.menuComponents.map { component ->
                when (component) {
                    is JMenuItem -> component.text
                    is JSeparator -> null
                    else -> error("Unexpected help component: ${component::class.java.name}")
                }
            },
        )

        help.item("How to Use").doClick()
        help.item("Contact Us").doClick()
        help.item("Check for Updates").doClick()
        help.item("Auto-check for Updates").doClick()
        help.item("About QTranslate").doClick()
        assertEquals(listOf("how", "contact", "check", "auto:false", "about"), calls)
    }

    private fun createMenu(
        config: Configuration = Configuration.DEFAULT,
        actions: MenuActions = createActions(),
    ) = MainMenuPopup(
        config = config,
        actions = actions,
        strings = MenuStrings(
            spellCheck = "Spell Checking",
            instantTranslation = "Instant Translation",
            extraOutput = "Extra Output",
            extraOutputNone = "None",
            extraOutputBackward = "Backward Translation",
            extraOutputSummarize = "Summarization",
            extraOutputRewrite = "Rewriting",
            viewOptions = "Options",
            dictionary = "Dictionary",
            isDictionaryPanelOpen = false,
            imageSearch = "Image Search",
            recognizeText = "Recognize Text (OCR)",
            history = "History",
            translateDocument = "Translate Document...",
            settings = "Settings...",
            help = "Help",
            howToUse = "How to Use",
            aboutQTranslate = "About QTranslate",
            contactUs = "Contact Us",
            autoCheckForUpdates = "Auto-check for Updates",
            checkForUpdates = "Check for Updates",
            exit = "Exit",
            layoutPresets = "Layout Presets",
            showHistoryControls = "Show History Bar",
            showLanguageBar = "Show Language Bar",
            showServicesPanel = "Show Services Panel",
            showStatusBar = "Show Status Bar",
        ),
        availableLayouts = listOf(
            LayoutPresetInfo("classic", "Classic"),
            LayoutPresetInfo("side_by_side", "Side By Side"),
            LayoutPresetInfo("compact", "Compact"),
        ),
    )

    private fun createActions(
        onToggleSpellCheck: (Boolean) -> Unit = {},
        onToggleInstantTranslation: (Boolean) -> Unit = {},
        onChangeExtraOutput: (ExtraOutputType) -> Unit = {},
        onShowDictionary: () -> Unit = {},
        onShowImageSearch: () -> Unit = {},
        onRecognizeText: () -> Unit = {},
        onShowHistory: () -> Unit = {},
        onTranslateDocument: () -> Unit = {},
        onShowSettings: () -> Unit = {},
        onShowHowToUse: () -> Unit = {},
        onShowAboutQTranslate: () -> Unit = {},
        onContactUs: () -> Unit = {},
        onToggleAutoCheckForUpdates: (Boolean) -> Unit = {},
        onCheckForUpdates: () -> Unit = {},
        onExitApplication: () -> Unit = {},
        onChangeLayoutPreset: (String) -> Unit = {},
        onToggleHistoryControls: (Boolean) -> Unit = {},
        onToggleLanguageBar: (Boolean) -> Unit = {},
        onToggleServicesPanel: (Boolean) -> Unit = {},
        onToggleStatusBar: (Boolean) -> Unit = {},
    ) = MenuActions(
        onToggleSpellCheck,
        onToggleInstantTranslation,
        onChangeExtraOutput,
        onShowDictionary,
        onShowImageSearch,
        onRecognizeText,
        onShowHistory,
        onTranslateDocument,
        onShowSettings,
        onShowHowToUse,
        onShowAboutQTranslate,
        onContactUs,
        onToggleAutoCheckForUpdates,
        onCheckForUpdates,
        onExitApplication,
        onChangeLayoutPreset,
        onToggleHistoryControls,
        onToggleLanguageBar,
        onToggleServicesPanel,
        onToggleStatusBar,
    )

    private fun JMenu.item(text: String) = menuComponents.filterIsInstance<JMenuItem>().single { it.text == text }

    private fun JPopupMenu.item(text: String) = components.filterIsInstance<JMenuItem>().single { it.text == text }
}

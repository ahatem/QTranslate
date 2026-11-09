package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Both the main menu and the tray menu surface the configured chords for the
 * actions they invoke; without hints the same items stay plain.
 */
class MenuShortcutHintsTest {

    private val hints = CommandShortcutHints.fromBindings(
        listOf(
            HotkeyBinding(HotkeyAction.SHOW_DICTIONARY, keyCode = KeyEvent.VK_X, modifiers = InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.OPEN_OCR, keyCode = KeyEvent.VK_Y, modifiers = InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.GLOBAL),
        )
    )

    @Test
    fun `main menu shows configured chords next to mapped actions`() {
        val menu = MainMenuPopup(
            config = Configuration.DEFAULT,
            actions = MenuActions(
                onToggleSpellCheck = {}, onToggleInstantTranslation = {}, onChangeExtraOutput = {},
                onShowDictionary = {}, onShowImageSearch = {}, onRecognizeText = {}, onShowHistory = {},
                onTranslateDocument = {}, onShowSettings = {}, onShowHowToUse = {}, onShowAboutQTranslate = {},
                onContactUs = {}, onToggleAutoCheckForUpdates = {}, onCheckForUpdates = {},
                onExitApplication = {}, onChangeLayoutPreset = {}, onToggleHistoryControls = {},
                onToggleLanguageBar = {}, onToggleServicesPanel = {}, onToggleStatusBar = {},
            ),
            strings = MenuStrings(
                spellCheck = "Spell", instantTranslation = "Instant", extraOutput = "Extra",
                extraOutputNone = "None", extraOutputBackward = "Backward", extraOutputSummarize = "Summary",
                extraOutputRewrite = "Rewrite", viewOptions = "Options", dictionary = "Dictionary",
                isDictionaryPanelOpen = false, imageSearch = "Image Search", recognizeText = "Recognize Text",
                history = "History", translateDocument = "Translate Document", settings = "Settings",
                help = "Help", howToUse = "How to Use", aboutQTranslate = "About", contactUs = "Contact",
                autoCheckForUpdates = "Auto-check", checkForUpdates = "Check", exit = "Exit",
                layoutPresets = "Layouts", showHistoryControls = "History bar", showLanguageBar = "Language bar",
                showServicesPanel = "Services", showStatusBar = "Status bar",
            ),
            availableLayouts = emptyList(),
            shortcutHints = hints,
        )

        val items = menu.components.filterIsInstance<JMenuItem>().associateBy { it.text }
        val dictionary = items.getValue("Dictionary")
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), dictionary.accelerator)
        assertEquals("Dictionary (Ctrl+X)", dictionary.toolTipText)
        assertEquals("Recognize Text (Ctrl+Y)", items.getValue("Recognize Text").toolTipText)
        assertNull(items.getValue("History").accelerator, "actions without a configured binding show no hint")
        assertNull(items.getValue("History").toolTipText)
    }

    @Test
    fun `tray menu shows configured chords next to mapped actions`() {
        val menu = TrayMenuPopup(
            actions = TrayMenuActions(
                onShowApplication = {}, onShowDictionary = {}, onShowImageSearch = {}, onRecognizeText = {},
                onTranslateDocument = {}, onShowHistory = {}, onSelectionBehaviorChanged = {},
                onShowSettings = {}, onToggleHotkeys = {}, onExitApplication = {},
            ),
            strings = TrayMenuStrings(
                showApplication = "Show", dictionary = "Dictionary", imageSearch = "Image Search",
                textRecognition = "Recognize Text", translateDocument = "Translate Document", history = "History",
                textSelection = "Text Selection", selectionBehaviorOff = "Off",
                selectionBehaviorIcon = "Icon", selectionBehaviorTranslate = "Translate",
                selectionBehaviorRead = "Read", settings = "Settings", toggleHotkeys = "Hotkeys", exit = "Exit",
            ),
            isHotkeysEnabled = true,
            selectionBehavior = SelectionBehavior.SHOW_ICON,
            shortcutHints = hints,
        )

        val items = menu.components.filterIsInstance<JMenuItem>().associateBy { it.text }
        val dictionary = items.getValue("Dictionary")
        assertNotNull(dictionary.accelerator)
        assertEquals("Dictionary (Ctrl+X)", dictionary.toolTipText)
        assertEquals("Recognize Text (Ctrl+Y)", items.getValue("Recognize Text").toolTipText)
    }
}

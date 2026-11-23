package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayPopupMenuTest {

    @Test
    fun `menu actions remain wired to their corresponding callbacks`() {
        val calls = mutableListOf<String>()
        val menu = createMenu(
            actions = TrayMenuActions(
                onShowApplication = { calls += "show" },
                onShowDictionary = { calls += "dictionary" },
                onShowImageSearch = { calls += "image" },
                onRecognizeText = { calls += "recognize" },
                onTranslateDocument = { calls += "document" },
                onShowHistory = { calls += "history" },
                onSelectionBehaviorChanged = { calls += "selection:$it" },
                onShowSettings = { calls += "settings" },
                onToggleHotkeys = { calls += "hotkeys:$it" },
                onExitApplication = { calls += "exit" },
            ),
        )

        menu.components.filterIsInstance<JMenuItem>()
            .filterNot { it is JMenu }
            .forEach { it.doClick() }

        assertEquals(
            listOf("show", "dictionary", "image", "recognize", "document", "history", "hotkeys:false", "settings", "exit"),
            calls,
        )
    }

    @Test
    fun `hotkey checkbox reflects its committed state and requests the new value`() {
        val calls = mutableListOf<Boolean>()
        val menu = createMenu(
            isHotkeysEnabled = false,
            actions = TrayMenuActions(
                onShowApplication = {},
                onShowDictionary = {},
                onShowImageSearch = {},
                onRecognizeText = {},
                onTranslateDocument = {},
                onShowHistory = {},
                onSelectionBehaviorChanged = {},
                onShowSettings = {},
                onToggleHotkeys = { calls += it },
                onExitApplication = {},
            ),
        )

        val checkbox = menu.components.filterIsInstance<JCheckBoxMenuItem>().single()
        assertFalse(checkbox.isSelected)

        checkbox.doClick()

        assertTrue(checkbox.isSelected)
        assertEquals(listOf(true), calls)
    }

    @Test
    fun `text selection submenu has one radio item per behavior, reflects the committed one and requests changes`() {
        val selected = mutableListOf<SelectionBehavior>()
        fun radioItems(menu: javax.swing.JPopupMenu) = menu.components.filterIsInstance<JMenu>().single()
            .menuComponents.filterIsInstance<JRadioButtonMenuItem>()

        SelectionBehavior.entries.forEach { committed ->
            val items = radioItems(
                createMenu(
                    selectionBehavior = committed,
                    actions = noActions.copy(onSelectionBehaviorChanged = { selected += it }),
                )
            )
            assertEquals(SelectionBehavior.entries.size, items.size)
            assertEquals(1, items.count { it.isSelected })
            assertTrue(items[committed.ordinal].isSelected, "committed $committed")

            SelectionBehavior.entries.forEachIndexed { index, behavior ->
                items[index].doClick()
                assertEquals(behavior, selected.last())
                assertEquals(1, items.count { it.isSelected })
            }
        }
    }

    @Test
    fun `menu items show the configured chords`() {
        val hints = CommandShortcutHints.fromBindings(
            listOf(
                HotkeyBinding(HotkeyAction.SHOW_DICTIONARY, keyCode = KeyEvent.VK_X, modifiers = InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.GLOBAL),
                HotkeyBinding(HotkeyAction.OPEN_OCR, keyCode = KeyEvent.VK_Y, modifiers = InputEvent.CTRL_DOWN_MASK, scope = HotkeyScope.GLOBAL),
            )
        )
        val items = createMenu(shortcutHints = hints).components.filterIsInstance<JMenuItem>().associateBy { it.text }

        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), items.getValue("Dictionary").accelerator)
        assertEquals("Dictionary (Ctrl+X)", items.getValue("Dictionary").toolTipText)
        assertEquals("Recognize Text (OCR) (Ctrl+Y)", items.getValue("Recognize Text (OCR)").toolTipText)
    }

    private val noActions = TrayMenuActions(
        onShowApplication = {},
        onShowDictionary = {},
        onShowImageSearch = {},
        onRecognizeText = {},
        onTranslateDocument = {},
        onShowHistory = {},
        onSelectionBehaviorChanged = {},
        onShowSettings = {},
        onToggleHotkeys = {},
        onExitApplication = {},
    )

    private fun createMenu(
        actions: TrayMenuActions = noActions,
        isHotkeysEnabled: Boolean = true,
        selectionBehavior: SelectionBehavior = SelectionBehavior.SHOW_ICON,
        shortcutHints: CommandShortcutHints = CommandShortcutHints.EMPTY,
    ) = TrayMenuPopup(
        actions = actions,
        strings = TrayMenuStrings(
            showApplication = "Show QTranslate",
            dictionary = "Dictionary",
            imageSearch = "Image Search",
            textRecognition = "Recognize Text (OCR)",
            translateDocument = "Translate Document...",
            history = "History",
            textSelection = "Text Selection",
            selectionBehaviorOff = "Off",
            selectionBehaviorIcon = "Show translation icon",
            selectionBehaviorTranslate = "Translate immediately",
            selectionBehaviorRead = "Translate and read aloud",
            settings = "Settings",
            toggleHotkeys = "Enable Global Hotkeys",
            exit = "Exit",
        ),
        isHotkeysEnabled = isHotkeysEnabled,
        selectionBehavior = selectionBehavior,
        shortcutHints = shortcutHints,
    )
}

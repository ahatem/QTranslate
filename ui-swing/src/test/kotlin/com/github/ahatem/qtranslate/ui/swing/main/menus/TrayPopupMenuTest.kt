package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayPopupMenuTest {

    @Test
    fun `menu groups tray actions in the expected order`() {
        val menu = createMenu()

        assertEquals(
            listOf(
                "Show QTranslate",
                null,
                "Dictionary",
                "Image Search",
                "Recognize Text (OCR)",
                "Translate Document...",
                "History",
                null,
                "Text Selection",
                "Enable Global Hotkeys",
                "Settings",
                null,
                "Exit",
            ),
            menu.components.map { component ->
                when (component) {
                    is JMenuItem -> component.text
                    is JSeparator -> null
                    else -> error("Unexpected tray menu component: ${component::class.java.name}")
                }
            },
        )
    }

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
    fun `text selection submenu contains one radio item for each behavior`() {
        val menu = createMenu()
        val submenu = menu.components.filterIsInstance<JMenu>().single()
        val items = submenu.menuComponents.filterIsInstance<JRadioButtonMenuItem>()

        assertEquals(
            listOf("Off", "Show translation icon", "Translate immediately", "Translate and read aloud"),
            items.map { it.text },
        )
        assertEquals(4, items.size)

        items[0].doClick()
        assertEquals(1, items.count { it.isSelected })
        items[1].doClick()
        assertEquals(1, items.count { it.isSelected })
        assertTrue(items[1].isSelected)
    }

    @Test
    fun `text selection submenu reflects every committed behavior`() {
        SelectionBehavior.entries.forEach { behavior ->
            val menu = createMenu(selectionBehavior = behavior)
            val items = menu.components.filterIsInstance<JMenu>().single()
                .menuComponents.filterIsInstance<JRadioButtonMenuItem>()

            assertEquals(1, items.count { it.isSelected })
            assertTrue(items[behavior.ordinal].isSelected)
        }
    }

    @Test
    fun `text selection items request their corresponding behavior`() {
        val selected = mutableListOf<SelectionBehavior>()
        val menu = createMenu(
            actions = TrayMenuActions(
                onShowApplication = {},
                onShowDictionary = {},
                onShowImageSearch = {},
                onRecognizeText = {},
                onTranslateDocument = {},
                onShowHistory = {},
                onSelectionBehaviorChanged = { selected += it },
                onShowSettings = {},
                onToggleHotkeys = {},
                onExitApplication = {},
            ),
        )
        val items = menu.components.filterIsInstance<JMenu>().single()
            .menuComponents.filterIsInstance<JRadioButtonMenuItem>()

        SelectionBehavior.entries.forEachIndexed { index, behavior ->
            items[index].doClick()
            assertEquals(behavior, selected.last())
        }
    }

    private fun createMenu(
        actions: TrayMenuActions = TrayMenuActions(
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
        ),
        isHotkeysEnabled: Boolean = true,
        selectionBehavior: SelectionBehavior = SelectionBehavior.SHOW_ICON,
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
    )
}

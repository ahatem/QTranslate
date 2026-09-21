package com.github.ahatem.qtranslate.ui.swing.main.menus

import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
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
                "History",
                null,
                "Settings",
                "Enable Global Hotkeys",
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
                onShowHistory = { calls += "history" },
                onShowSettings = { calls += "settings" },
                onToggleHotkeys = { calls += "hotkeys:$it" },
                onExitApplication = { calls += "exit" },
            ),
        )

        menu.components.filterIsInstance<JMenuItem>().forEach { it.doClick() }

        assertEquals(
            listOf("show", "dictionary", "image", "recognize", "history", "settings", "hotkeys:false", "exit"),
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
                onShowHistory = {},
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

    private fun createMenu(
        actions: TrayMenuActions = TrayMenuActions(
            onShowApplication = {},
            onShowDictionary = {},
            onShowImageSearch = {},
            onRecognizeText = {},
            onShowHistory = {},
            onShowSettings = {},
            onToggleHotkeys = {},
            onExitApplication = {},
        ),
        isHotkeysEnabled: Boolean = true,
    ) = TrayMenuPopup(
        actions = actions,
        strings = TrayMenuStrings(
            showApplication = "Show QTranslate",
            dictionary = "Dictionary",
            imageSearch = "Image Search",
            textRecognition = "Recognize Text (OCR)",
            history = "History",
            settings = "Settings",
            toggleHotkeys = "Enable Global Hotkeys",
            exit = "Exit",
        ),
        isHotkeysEnabled = isHotkeysEnabled,
    )
}

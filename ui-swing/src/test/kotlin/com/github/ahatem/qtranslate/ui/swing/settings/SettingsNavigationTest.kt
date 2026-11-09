package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sidebar structure is the contract the tree, the search index and external callers all read:
 * page order, grouping, and which entries carry icons.
 */
class SettingsNavigationTest {

    // Labels are the keys themselves: the structure under test is locale-independent.
    private val navigation = SettingsNavigation { key -> "settings_dialog_sidebar.$key" }

    @Test
    fun `the sidebar keeps the documented order and grouping`() {
        assertEquals(
            listOf(
                Nav.Page("settings_dialog_sidebar.general", Icons.GENERAL),
                Nav.Page("settings_dialog_sidebar.appearance", Icons.APPEARANCE),
                Nav.Group(
                    "settings_dialog_sidebar.group_translation", listOf(
                        Nav.Page("settings_dialog_sidebar.services", Icons.SERVICE),
                        Nav.Page("settings_dialog_sidebar.behavior", Icons.TRANSLATE),
                        Nav.Page("settings_dialog_sidebar.languages", Icons.LANGUAGE),
                    )
                ),
                Nav.Group(
                    "settings_dialog_sidebar.group_interface", listOf(
                        Nav.Page("settings_dialog_sidebar.layout", Icons.LAYOUT),
                        Nav.Page("settings_dialog_sidebar.popups", Icons.POPUP),
                    )
                ),
                Nav.Page("settings_dialog_sidebar.ocr", Icons.OCR),
                Nav.Page("settings_dialog_sidebar.hotkeys", Icons.KEYBOARD),
                Nav.Page("settings_dialog_sidebar.plugins", Icons.PLUGIN),
                Nav.Page("settings_dialog_sidebar.network", Icons.NETWORK),
            ),
            navigation.tree,
        )
    }

    @Test
    fun `pages flatten groups in sidebar order without the groups themselves`() {
        assertEquals(
            listOf(
                "general", "appearance", "services", "behavior", "languages",
                "layout", "popups", "ocr", "hotkeys", "plugins", "network",
            ).map { "settings_dialog_sidebar.$it" },
            navigation.pages.map { it.label },
        )
    }

    @Test
    fun `icons are mapped for pages only`() {
        assertEquals(navigation.pages.map { it.label }, navigation.iconPaths.keys.toList())
        navigation.pages.forEach { page ->
            assertEquals(page.iconPath, navigation.iconPaths.getValue(page.label))
        }
    }

    @Test
    fun `groups are recognized by their labels`() {
        assertTrue(navigation.isGroup("settings_dialog_sidebar.group_translation"))
        assertTrue(navigation.isGroup("settings_dialog_sidebar.group_interface"))
        assertFalse(navigation.isGroup("settings_dialog_sidebar.general"))
        assertFalse(navigation.isGroup("settings_dialog_sidebar.group_unknown"))
    }

    @Test
    fun `a group resolves to its first child, a page or an unknown label to nothing`() {
        assertEquals(
            "settings_dialog_sidebar.services",
            navigation.firstChildOf("settings_dialog_sidebar.group_translation")?.label,
        )
        assertEquals(
            "settings_dialog_sidebar.layout",
            navigation.firstChildOf("settings_dialog_sidebar.group_interface")?.label,
        )
        assertNull(navigation.firstChildOf("settings_dialog_sidebar.general"))
        assertNull(navigation.firstChildOf("settings_dialog_sidebar.group_unknown"))
    }
}

package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.SelectionContextMenu
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A menu item whose label resolves to its own key reads like a feature that shipped without its
 * text, so the keys the selection menu asks for are checked against the bundled English strings.
 */
class DictionarySelectionMenuLabelsTest {

    private val strings = LanguageTomlParser()
        .parse(File("../core/src/main/resources/localization/embedded_en.toml").readText())
        .entries

    @Test
    fun `the menu's keys are shared keys that resolve to real text`() {
        assertEquals("copy", SelectionContextMenu.COPY_KEY)
        assertEquals("select_all", SelectionContextMenu.SELECT_ALL_KEY)

        listOf(SelectionContextMenu.COPY_KEY, SelectionContextMenu.SELECT_ALL_KEY).forEach { key ->
            val value = strings["common.$key"]
            assertTrue(
                value != null && value.isNotBlank() && value != key,
                "common.$key must resolve to text, not to itself (got \"$value\")"
            )
        }
    }
}
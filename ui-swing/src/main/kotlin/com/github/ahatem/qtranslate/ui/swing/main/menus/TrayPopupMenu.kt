package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import java.awt.event.ItemEvent
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator

data class TrayMenuStrings(
    val showApplication: String,
    val dictionary: String,
    val imageSearch: String,
    val textRecognition: String,
    val translateDocument: String,
    val history: String,
    val textSelection: String,
    val selectionBehaviorOff: String,
    val selectionBehaviorIcon: String,
    val selectionBehaviorTranslate: String,
    val selectionBehaviorRead: String,
    val settings: String,
    val toggleHotkeys: String,
    val exit: String
)

data class TrayMenuActions(
    val onShowApplication: () -> Unit,
    val onShowDictionary: () -> Unit,
    val onShowImageSearch: () -> Unit,
    val onRecognizeText: () -> Unit,
    val onTranslateDocument: () -> Unit,
    val onShowHistory: () -> Unit,
    val onSelectionBehaviorChanged: (SelectionBehavior) -> Unit,
    val onShowSettings: () -> Unit,
    val onToggleHotkeys: (Boolean) -> Unit,
    val onExitApplication: () -> Unit
)

class TrayMenuPopup(
    private val actions: TrayMenuActions,
    private val strings: TrayMenuStrings,
    private val isHotkeysEnabled: Boolean,
    private val selectionBehavior: SelectionBehavior,
) : JPopupMenu() {
    init {
        add(JMenuItem(strings.showApplication).apply {
            addActionListener { actions.onShowApplication() }
        })

        add(JSeparator())

        add(JMenuItem(strings.dictionary).apply {
            addActionListener { actions.onShowDictionary() }
        })

        add(JMenuItem(strings.imageSearch).apply {
            addActionListener { actions.onShowImageSearch() }
        })

        add(JMenuItem(strings.textRecognition).apply {
            addActionListener { actions.onRecognizeText() }
        })

        add(JMenuItem(strings.translateDocument).apply {
            addActionListener { actions.onTranslateDocument() }
        })

        add(JMenuItem(strings.history).apply {
            addActionListener { actions.onShowHistory() }
        })

        add(JSeparator())

        add(JMenu(strings.textSelection).apply {
            val group = ButtonGroup()
            listOf(
                SelectionBehavior.OFF to strings.selectionBehaviorOff,
                SelectionBehavior.SHOW_ICON to strings.selectionBehaviorIcon,
                SelectionBehavior.TRANSLATE to strings.selectionBehaviorTranslate,
                SelectionBehavior.TRANSLATE_AND_READ to strings.selectionBehaviorRead,
            ).forEach { (behavior, text) ->
                add(JRadioButtonMenuItem(text).apply {
                    isSelected = behavior == selectionBehavior
                    group.add(this)
                    addActionListener { actions.onSelectionBehaviorChanged(behavior) }
                })
            }
        })

        add(JCheckBoxMenuItem(strings.toggleHotkeys, isHotkeysEnabled).apply {
            addItemListener { e -> actions.onToggleHotkeys(e.stateChange == ItemEvent.SELECTED) }
        })

        add(JMenuItem(strings.settings).apply {
            addActionListener { actions.onShowSettings() }
        })

        add(JSeparator())

        add(JMenuItem(strings.exit).apply {
            addActionListener { actions.onExitApplication() }
        })
    }
}

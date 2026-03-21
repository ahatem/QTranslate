package com.github.ahatem.qtranslate.ui.swing.main.menus

import java.awt.event.ItemEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator

data class TrayMenuStrings(
    val showApplication: String,
    val dictionary: String,
    val textRecognition: String,
    val history: String,
    val settings: String,
    val toggleHotkeys: String,
    val exit: String
)

data class TrayMenuActions(
    val onShowApplication: () -> Unit,
    val onShowDictionary: () -> Unit,
    val onRecognizeText: () -> Unit,
    val onShowHistory: () -> Unit,
    val onShowSettings: () -> Unit,
    val onToggleHotkeys: (Boolean) -> Unit,
    val onExitApplication: () -> Unit
)

class TrayMenuPopup(
    private val actions: TrayMenuActions,
    private val strings: TrayMenuStrings,
    private val isHotkeysEnabled: Boolean
) : JPopupMenu() {
    init {
        add(JMenuItem(strings.showApplication).apply {
            addActionListener { actions.onShowApplication() }
        })

        add(JMenuItem(strings.dictionary).apply {
            isEnabled = false // Feature not yet implemented
            addActionListener { actions.onShowDictionary() }
        })

        add(JMenuItem(strings.textRecognition).apply {
            addActionListener { actions.onRecognizeText() }
        })

        add(JMenuItem(strings.history).apply {
            addActionListener { actions.onShowHistory() }
        })

        add(JMenuItem(strings.settings).apply {
            addActionListener { actions.onShowSettings() }
        })

        add(JSeparator())

        add(JCheckBoxMenuItem(strings.toggleHotkeys, isHotkeysEnabled).apply {
            addItemListener { e -> actions.onToggleHotkeys(e.stateChange == ItemEvent.SELECTED) }
        })

        add(JSeparator())

        add(JMenuItem(strings.exit).apply {
            addActionListener { actions.onExitApplication() }
        })
    }
}
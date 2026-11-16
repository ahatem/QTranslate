package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
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
    val exit: String,
    /** Empty hides the item, for a frame built without the conversation feature. */
    val conversations: String = ""
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
    val onExitApplication: () -> Unit,
    val onShowConversations: () -> Unit = {}
)

class TrayMenuPopup(
    private val actions: TrayMenuActions,
    private val strings: TrayMenuStrings,
    private val isHotkeysEnabled: Boolean,
    private val selectionBehavior: SelectionBehavior,
    private val shortcutHints: CommandShortcutHints = CommandShortcutHints.EMPTY,
) : JPopupMenu() {
    init {
        add(JMenuItem(strings.showApplication).apply {
            addActionListener { actions.onShowApplication() }
        })

        add(JSeparator())

        add(JMenuItem(strings.dictionary).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.SHOW_DICTIONARY)
            addActionListener { actions.onShowDictionary() }
        })

        add(JMenuItem(strings.imageSearch).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.SHOW_IMAGES)
            addActionListener { actions.onShowImageSearch() }
        })

        add(JMenuItem(strings.textRecognition).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.OPEN_OCR)
            addActionListener { actions.onRecognizeText() }
        })

        add(JMenuItem(strings.translateDocument).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.TRANSLATE_DOCUMENT)
            addActionListener { actions.onTranslateDocument() }
        })

        if (strings.conversations.isNotEmpty()) {
            add(JMenuItem(strings.conversations).apply {
                addActionListener { actions.onShowConversations() }
            })
        }

        add(JMenuItem(strings.history).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.SHOW_HISTORY)
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
            applyShortcutHint(shortcutHints, HotkeyAction.OPEN_SETTINGS)
            addActionListener { actions.onShowSettings() }
        })

        add(JSeparator())

        add(JMenuItem(strings.exit).apply {
            addActionListener { actions.onExitApplication() }
        })
    }
}

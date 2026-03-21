package com.github.ahatem.qtranslate.ui.swing.main.menus


import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import javax.swing.*

data class LayoutPresetInfo(
    val id: String,
    val name: String
)

data class MenuStrings(
    val spellCheck: String,
    val instantTranslation: String,
    val extraOutput: String,
    val viewOptions: String,
    val dictionary: String,
    val history: String,
    val settings: String,
    val help: String,
    val howToUse: String,
    val aboutQTranslate: String,
    val contactUs: String,
    val autoCheckForUpdates: String,
    val checkForUpdates: String,
    val exit: String,
    val layoutPresets: String,
    val showHistoryControls: String,
    val showLanguageBar: String,
    val showServicesPanel: String,
    val showStatusBar: String,
)

data class MenuActions(
    val onToggleSpellCheck: (Boolean) -> Unit,
    val onToggleInstantTranslation: (Boolean) -> Unit,
    val onToggleExtraOutput: (Boolean) -> Unit,
    val onShowDictionary: () -> Unit,
    val onShowHistory: () -> Unit,
    val onShowSettings: () -> Unit,

    val onShowHowToUse: () -> Unit,
    val onShowAboutQTranslate: () -> Unit,
    val onContactUs: () -> Unit,
    val onToggleAutoCheckForUpdates: (Boolean) -> Unit,
    val onCheckForUpdates: () -> Unit,

    val onExitApplication: () -> Unit,
    val onChangeLayoutPreset: (String) -> Unit,
    val onToggleHistoryControls: (Boolean) -> Unit,
    val onToggleLanguageBar: (Boolean) -> Unit,
    val onToggleServicesPanel: (Boolean) -> Unit,
    val onToggleStatusBar: (Boolean) -> Unit
)

class LayoutPresetsMenu(
    title: String,
    private val availableLayouts: List<LayoutPresetInfo>,
    private val activeLayoutId: String,
    private val onLayoutSelected: (String) -> Unit
) : JMenu(title) {
    init {
        for (layout in availableLayouts) {
            add(JCheckBoxMenuItem(layout.name).apply {
                isSelected = layout.id == activeLayoutId
                addActionListener {
                    println("clicked on layout ${layout.id}")
                    onLayoutSelected(layout.id)
                }
            })
        }
    }
}

class ViewOptionsMenu(
    private val config: Configuration,
    private val actions: MenuActions,
    private val strings: MenuStrings,
    private val availableLayouts: List<LayoutPresetInfo>
) : JMenu(strings.viewOptions) {
    init {
        add(
            LayoutPresetsMenu(
                strings.layoutPresets,
                availableLayouts,
                config.layoutPresetId,
                actions.onChangeLayoutPreset
            )
        )
        add(JSeparator())
        add(JCheckBoxMenuItem(strings.showHistoryControls).apply {
            isSelected = config.toolbarVisibility.isHistoryBarVisible
            addActionListener { actions.onToggleHistoryControls(isSelected) }
        })
        add(JCheckBoxMenuItem(strings.showLanguageBar).apply {
            isSelected = config.toolbarVisibility.isLanguageBarVisible
            addActionListener { actions.onToggleLanguageBar(isSelected) }
        })
        add(JCheckBoxMenuItem(strings.showServicesPanel).apply {
            isSelected = config.toolbarVisibility.isServicesPanelVisible
            addActionListener { actions.onToggleServicesPanel(isSelected) }
        })
        add(JCheckBoxMenuItem(strings.showStatusBar).apply {
            isSelected = config.toolbarVisibility.isStatusBarVisible
            addActionListener { actions.onToggleStatusBar(isSelected) }
        })
    }
}

class MainMenuPopup(
    private val config: Configuration,
    private val actions: MenuActions,
    private val strings: MenuStrings,
    private val availableLayouts: List<LayoutPresetInfo>
) : JPopupMenu() {
    init {
        add(JCheckBoxMenuItem(strings.spellCheck).apply {
            isSelected = config.isSpellCheckingEnabled
            addActionListener { actions.onToggleSpellCheck(isSelected) }
        })
        add(JCheckBoxMenuItem(strings.instantTranslation).apply {
            isSelected = config.isInstantTranslationEnabled
            addActionListener { actions.onToggleInstantTranslation(isSelected) }
        })
        add(JCheckBoxMenuItem(strings.extraOutput).apply {
            isSelected = config.extraOutputType != ExtraOutputType.None
            addActionListener { actions.onToggleExtraOutput(isSelected) }
        })
        add(ViewOptionsMenu(config, actions, strings, availableLayouts))
        add(JSeparator())
        add(JMenuItem(strings.dictionary).apply {
            isEnabled = false
            addActionListener { actions.onShowDictionary() }
        })
        add(JMenuItem(strings.history).apply {
            addActionListener { actions.onShowHistory() }
        })
        add(JMenuItem(strings.settings).apply {
            addActionListener { actions.onShowSettings() }
        })
        add(JMenu(strings.help).apply {
            add(JMenuItem(strings.howToUse).apply {
                addActionListener { actions.onShowHowToUse() }
            })

            add(JMenuItem(strings.aboutQTranslate).apply {
                addActionListener { actions.onShowAboutQTranslate() }
            })

            add(JMenuItem(strings.contactUs).apply {
                addActionListener { actions.onContactUs() }
            })

            add(JSeparator())

            add(JCheckBoxMenuItem(strings.autoCheckForUpdates).apply {
                isSelected = config.autoCheckForUpdates
                addActionListener { actions.onToggleAutoCheckForUpdates(isSelected) }
            })

            add(JMenuItem(strings.checkForUpdates).apply {
                addActionListener { actions.onCheckForUpdates() }
            })
        })
        add(JSeparator())
        add(JMenuItem(strings.exit).apply {
            addActionListener { actions.onExitApplication() }
        })
    }
}
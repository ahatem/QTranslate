package com.github.ahatem.qtranslate.ui.swing.main.menus


import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator

data class LayoutPresetInfo(
    val id: String,
    val name: String
)

data class MenuStrings(
    val spellCheck: String,
    val instantTranslation: String,
    val extraOutput: String,
    val extraOutputNone: String,
    val extraOutputBackward: String,
    val extraOutputSummarize: String,
    val extraOutputRewrite: String,
    val viewOptions: String,
    val dictionary: String,
    val isDictionaryPanelOpen: Boolean,
    val imageSearch: String,
    val recognizeText: String,
    val history: String,
    val translateDocument: String,
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
    val onChangeExtraOutput: (ExtraOutputType) -> Unit,
    val onShowDictionary: () -> Unit,
    val onShowImageSearch: () -> Unit,
    val onRecognizeText: () -> Unit,
    val onShowHistory: () -> Unit,
    val onTranslateDocument: () -> Unit,
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
        val group = ButtonGroup()
        for (layout in availableLayouts) {
            add(JRadioButtonMenuItem(layout.name).apply {
                isSelected = layout.id == activeLayoutId
                group.add(this)
                addActionListener { onLayoutSelected(layout.id) }
            })
        }
    }
}

class ExtraOutputMenu(
    title: String,
    private val activeType: ExtraOutputType,
    private val strings: MenuStrings,
    private val onTypeSelected: (ExtraOutputType) -> Unit
) : JMenu(title) {
    init {
        val group = ButtonGroup()
        listOf(
            ExtraOutputType.None to strings.extraOutputNone,
            ExtraOutputType.BackwardTranslate to strings.extraOutputBackward,
            ExtraOutputType.Summarize to strings.extraOutputSummarize,
            ExtraOutputType.Rewrite to strings.extraOutputRewrite,
        ).forEach { (type, label) ->
            add(JRadioButtonMenuItem(label).apply {
                isSelected = type == activeType
                group.add(this)
                addActionListener { onTypeSelected(type) }
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
        add(ExtraOutputMenu(strings.extraOutput, config.extraOutputType, strings, actions.onChangeExtraOutput))
        add(ViewOptionsMenu(config, actions, strings, availableLayouts))
        add(JSeparator())
        add(JCheckBoxMenuItem(strings.dictionary).apply {
            isSelected = strings.isDictionaryPanelOpen
            addActionListener { actions.onShowDictionary() }
        })
        add(JMenuItem(strings.imageSearch).apply {
            addActionListener { actions.onShowImageSearch() }
        })
        add(JMenuItem(strings.recognizeText).apply {
            addActionListener { actions.onRecognizeText() }
        })
        add(JMenuItem(strings.translateDocument).apply {
            addActionListener { actions.onTranslateDocument() }
        })
        add(JMenuItem(strings.history).apply {
            addActionListener { actions.onShowHistory() }
        })
        add(JSeparator())
        add(JMenuItem(strings.settings).apply {
            addActionListener { actions.onShowSettings() }
        })
        add(JMenu(strings.help).apply {
            add(JMenuItem(strings.howToUse).apply {
                addActionListener { actions.onShowHowToUse() }
            })

            add(JMenuItem(strings.contactUs).apply {
                addActionListener { actions.onContactUs() }
            })

            add(JSeparator())

            add(JMenuItem(strings.checkForUpdates).apply {
                addActionListener { actions.onCheckForUpdates() }
            })

            add(JCheckBoxMenuItem(strings.autoCheckForUpdates).apply {
                isSelected = config.autoCheckForUpdates
                addActionListener { actions.onToggleAutoCheckForUpdates(isSelected) }
            })

            add(JSeparator())

            add(JMenuItem(strings.aboutQTranslate).apply {
                addActionListener { actions.onShowAboutQTranslate() }
            })
        })
        add(JSeparator())
        add(JMenuItem(strings.exit).apply {
            addActionListener { actions.onExitApplication() }
        })
    }
}

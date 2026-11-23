package com.github.ahatem.qtranslate.ui.swing.main.menus


import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import com.github.ahatem.qtranslate.core.settings.data.profileMatching
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

/** The words of the layout entries the Options menu adds to the panes and the status bar. */
data class LayoutMenuStrings(
    val region: RegionMenuStrings,
    /** Where the second result sits. */
    val secondResult: SecondResultMenuStrings,
    /** Heading above the saved layouts. */
    val savedLayouts: String,
    val saveProfile: String,
    val manageLayouts: String,
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
    val layoutComparisonAvailable: Boolean = true,
    val layoutComparisonUnavailableHint: String = "",
    val layoutComparisonConfigureLabel: String = "",
    val showStatusBar: String,
    /** The region submenus and saved layouts; the menu has none of them when this is null. */
    val layoutMenu: LayoutMenuStrings? = null,
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
    val onToggleStatusBar: (Boolean) -> Unit,
    val onOpenServicesSettings: () -> Unit = {},
    /** Stores a whole new window layout: a region menu's choice, or a saved layout being applied. */
    val onChangeLayout: (WindowLayout) -> Unit = {},
    /** Asks for a name and saves the window's layout as a profile. */
    val onSaveLayoutProfile: () -> Unit = {},
    /** Opens Settings on the Layout page. */
    val onManageLayouts: () -> Unit = {},
)

class LayoutPresetsMenu(
    title: String,
    private val availableLayouts: List<LayoutPresetInfo>,
    private val activeLayoutId: String,
    private val onLayoutSelected: (String) -> Unit,
    private val comparisonAvailable: Boolean = true,
    private val comparisonUnavailableHint: String = "",
    /** The unavailable entry's own label -- an action ("Set up translators...") rather than a name. */
    private val comparisonConfigureLabel: String = "",
    /** Where the unavailable entry sends the user, instead of doing nothing. */
    private val onOpenServicesSettings: () -> Unit = {}
) : JMenu(title) {
    init {
        val group = ButtonGroup()
        for (layout in availableLayouts) {
            // Comparison stays visible while fewer than two translators are usable, but as an
            // actionable item rather than a disabled one: its own label says what to do, and
            // clicking it takes the user to fix it instead of doing nothing.
            val unavailable = layout.id == LayoutPresetIds.COMPARISON && !comparisonAvailable
            if (unavailable) {
                add(JMenuItem(comparisonConfigureLabel).apply {
                    toolTipText = comparisonUnavailableHint
                    addActionListener { onOpenServicesSettings() }
                })
                continue
            }
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
                config.windowLayout.panes.presetId,
                actions.onChangeLayoutPreset,
                strings.layoutComparisonAvailable,
                strings.layoutComparisonUnavailableHint,
                strings.layoutComparisonConfigureLabel,
                actions.onOpenServicesSettings
            )
        )
        val layoutMenu = strings.layoutMenu
        if (layoutMenu != null) {
            add(JSeparator())
            val effectivePanes = config.windowLayout.effectivePanes(strings.layoutComparisonAvailable)
            WindowRegion.entries.forEach { region ->
                add(RegionMenu(region, config.windowLayout, layoutMenu.region, actions.onChangeLayout, effectivePanes).menu())
            }
            add(
                SecondResultMenu(
                    config.windowLayout, effectivePanes, config.extraOutputType,
                    layoutMenu.secondResult, actions.onChangeLayout,
                ).menu()
            )
        }
        add(JSeparator())
        add(JCheckBoxMenuItem(strings.showStatusBar).apply {
            isSelected = config.windowLayout.showStatusBar
            addActionListener { actions.onToggleStatusBar(isSelected) }
        })
        if (layoutMenu != null) addSavedLayouts(layoutMenu)
    }

    /**
     * The saved layouts as radio items, the one equal to the window checked, then the entries that
     * save a new one and open Settings to manage them.
     */
    private fun addSavedLayouts(layoutMenu: LayoutMenuStrings) {
        add(JSeparator())
        if (config.layoutProfiles.isNotEmpty()) {
            add(JMenuItem(layoutMenu.savedLayouts).apply {
                name = RegionMenu.HEADING
                isEnabled = false
            })
            val group = ButtonGroup()
            val active = config.layoutProfiles.profileMatching(config.windowLayout)
            config.layoutProfiles.forEach { profile ->
                add(JRadioButtonMenuItem(profile.name).apply {
                    name = "$PROFILE_PREFIX${profile.name}"
                    isSelected = profile === active
                    group.add(this)
                    addActionListener { actions.onChangeLayout(profile.layout) }
                })
            }
        }
        add(JMenuItem(layoutMenu.saveProfile).apply {
            name = RegionMenu.SAVE_PROFILE
            addActionListener { actions.onSaveLayoutProfile() }
        })
        add(JMenuItem(layoutMenu.manageLayouts).apply {
            name = MANAGE_LAYOUTS
            addActionListener { actions.onManageLayouts() }
        })
    }

    companion object {
        /** Prefix of the name of a saved layout's item, followed by the layout's name. */
        const val PROFILE_PREFIX = "profile:"
        const val MANAGE_LAYOUTS = "manage_layouts"
    }
}

class MainMenuPopup(
    private val config: Configuration,
    private val actions: MenuActions,
    private val strings: MenuStrings,
    private val availableLayouts: List<LayoutPresetInfo>,
    private val shortcutHints: CommandShortcutHints = CommandShortcutHints.EMPTY
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
            applyShortcutHint(shortcutHints, HotkeyAction.SHOW_DICTIONARY)
            addActionListener { actions.onShowDictionary() }
        })
        add(JMenuItem(strings.imageSearch).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.SHOW_IMAGES)
            addActionListener { actions.onShowImageSearch() }
        })
        add(JMenuItem(strings.recognizeText).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.OPEN_OCR)
            addActionListener { actions.onRecognizeText() }
        })
        add(JMenuItem(strings.translateDocument).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.TRANSLATE_DOCUMENT)
            addActionListener { actions.onTranslateDocument() }
        })
        add(JMenuItem(strings.history).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.SHOW_HISTORY)
            addActionListener { actions.onShowHistory() }
        })
        add(JSeparator())
        add(JMenuItem(strings.settings).apply {
            applyShortcutHint(shortcutHints, HotkeyAction.OPEN_SETTINGS)
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

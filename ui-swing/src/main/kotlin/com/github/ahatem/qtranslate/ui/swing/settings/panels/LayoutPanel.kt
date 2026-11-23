package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.FeatureHost
import com.github.ahatem.qtranslate.core.settings.data.LayoutProfile
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.WindowFeature
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowPreset
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import com.github.ahatem.qtranslate.core.settings.data.cleanProfileName
import com.github.ahatem.qtranslate.core.settings.data.effectivePaneLayout
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import com.github.ahatem.qtranslate.core.settings.data.isFull
import com.github.ahatem.qtranslate.core.settings.data.profileMatching
import com.github.ahatem.qtranslate.core.settings.data.profileNamed
import com.github.ahatem.qtranslate.core.settings.data.withLayoutProfiles
import com.github.ahatem.qtranslate.core.settings.data.withProfileRemoved
import com.github.ahatem.qtranslate.core.settings.data.withProfileRenamed
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.main.menus.LayoutProfileSaver
import com.github.ahatem.qtranslate.ui.swing.main.menus.ProfileManagementPrompts
import com.github.ahatem.qtranslate.ui.swing.main.menus.RegionMenu
import com.github.ahatem.qtranslate.ui.swing.main.menus.SecondResultMenu
import com.github.ahatem.qtranslate.ui.swing.main.menus.SwingProfilePrompts
import com.github.ahatem.qtranslate.ui.swing.main.menus.profileStrings
import com.github.ahatem.qtranslate.ui.swing.main.menus.regionMenuStrings
import com.github.ahatem.qtranslate.ui.swing.main.menus.secondResultMenuStrings
import com.github.ahatem.qtranslate.ui.swing.shared.layout.LayoutTexts
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

/**
 * The main window, as an outline of itself.
 *
 * Panes are chosen at the top. The outline shows the window as the draft would build it; clicking
 * a block opens the same menu the window's own row offers, built against the draft, so there is one
 * way to arrange a region. The starting points are partial recipes: a card writes only the values
 * it names, so it counts as applied only while applying it would change nothing. Saved layouts keep
 * whole windows.
 *
 * Every write on this page is one whole [WindowLayout] through [withWindowLayout], or the list of
 * saved layouts through [withLayoutProfiles], dispatched as a draft change.
 */
class LayoutPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    private val availableTranslatorIds: () -> List<String> = { emptyList() },
    /** Invoked when the user acts on the unavailable-Comparison entry, to send them to fix it. */
    private val onOpenServicesSettings: () -> Unit = {},
    /** The questions asked while saving, renaming and deleting. Defaults to dialogs over this page. */
    prompts: ProfileManagementPrompts? = null,
) : SettingsPanel() {

    private val texts = LayoutTexts(localizationManager)
    private val prompts: ProfileManagementPrompts = prompts ?: SwingProfilePrompts(
        parent = { SwingUtilities.getWindowAncestor(this) },
        strings = { localizationManager.profileStrings() },
    )

    private var comparisonAvailable = true
    private var shownLayout = WindowLayout.DEFAULT
    private var shownPanes = PaneLayout.STACKED
    private var secondResultType = ExtraOutputType.None
    private var shownProfiles: List<LayoutProfile> = emptyList()

    /** What the last rebuild of the outline, the list of hidden regions and the profiles was for. */
    private var shownSignature: Any? = null

    private val paneButtons = LinkedHashMap<PaneLayout, JToggleButton>()
    private val cards = LinkedHashMap<WindowPreset, Card>()
    private val wrapped = mutableListOf<WrappedText>()

    private val outline = LayoutOutline(
        label = ::blockName,
        tooltip = { block -> if (block.isArrangeable) localizationManager.getString("settings_window.layout_outline_block_tip", blockName(block)) else null },
        onOpen = { block, invoker -> present(popupFor(block), invoker) },
    )
    private val hiddenList = stackedColumn()
    private val currentLabel = JLabel()
    private val profileList = stackedColumn()
    private val saveButton = JButton(localizationManager.getString("main_window_main_menu.region_save_profile"))
    private val saveHint = JLabel()

    /** A column whose rows start at the leading edge and fill the width, so it mirrors with the page. */
    private fun stackedColumn() = JPanel(GridBagLayout()).apply { isOpaque = false }

    /** Adds [row] under the rows already in [column]. */
    private fun JPanel.addRow(row: JComponent) {
        add(
            row,
            GridBagConstraints().apply {
                gridx = 0
                gridy = componentCount
                weightx = 1.0
                anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.HORIZONTAL
            },
        )
    }

    /** Shows a popup next to its block. A seam so a test can take the popup without a screen. */
    internal var presenter: (JPopupMenu, JComponent) -> Unit = { popup, invoker ->
        val x = if (invoker.componentOrientation.isLeftToRight) 0 else invoker.width - popup.preferredSize.width
        popup.show(invoker, x, invoker.height)
    }

    init {
        buildUI()
    }

    private fun buildUI() {
        addSeparator(localizationManager.getString("settings_window.layout_group"))
        addRow(localizationManager.getString("settings_window.layout_row_panes"), panesControl())

        addSeparator(localizationManager.getString("settings_window.layout_outline_group"))
        addHint(localizationManager.getString("settings_window.layout_outline_hint"))
        // The outline sits at the leading edge at its own width rather than stretching with the page.
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(
            JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                isOpaque = false
                add(outline)
            }
        )
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(hiddenList)

        addSeparator(localizationManager.getString("settings_window.layout_starting_points_group"))
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(cardGrid())
        currentLabel.font = SettingsVisuals.hintFont(currentLabel.font)
        currentLabel.foreground = SettingsVisuals.secondaryText()
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(currentLabel)

        addSeparator(localizationManager.getString("main_window_main_menu.layout_saved_layouts"))
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(profileList)
        saveButton.addActionListener { if (!isUpdatingFromState) saveCurrent() }
        gb.nextRow().add(saveButton)
        saveHint.font = SettingsVisuals.hintFont(saveHint.font)
        saveHint.foreground = SettingsVisuals.secondaryText()
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(saveHint)
        registerSearchEntry(saveButton.text, saveButton)

        finishLayout()
    }

    // ---- Panes ----

    private fun panesControl(): JPanel {
        val group = ButtonGroup()
        return JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
            isOpaque = false
            PaneLayout.entries.forEach { pane ->
                val button = JToggleButton(paneTitle(pane)).apply {
                    name = "panes:$pane"
                    putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT)
                    addActionListener { if (!isUpdatingFromState) choosePanes(pane) }
                }
                group.add(button)
                paneButtons[pane] = button
                add(button)
            }
        }
    }

    private fun paneTitle(pane: PaneLayout): String =
        if (pane == PaneLayout.COMPARISON && !comparisonAvailable) {
            localizationManager.getString("settings_window.layout_comparison_configure")
        } else {
            texts.pane(pane)
        }

    private fun choosePanes(pane: PaneLayout) {
        if (pane == PaneLayout.COMPARISON && !comparisonAvailable) {
            // Not a layout the window can build: send the user to fix it and keep the draft as it is.
            syncPanes()
            onOpenServicesSettings()
        } else {
            writeLayout { it.copy(panes = pane) }
            syncPanes()
        }
    }

    /** Selects what the window builds, which is Stacked while a requested Comparison cannot be drawn. */
    private fun syncPanes() = withoutTrigger {
        paneButtons.forEach { (pane, button) ->
            button.text = paneTitle(pane)
            button.isSelected = pane == shownPanes
            // Only an unavailable Comparison has a reason to give; an available one has nothing to explain.
            button.toolTipText =
                if (pane == PaneLayout.COMPARISON && !comparisonAvailable) {
                    localizationManager.getString("settings_window.layout_comparison_unavailable")
                } else {
                    null
                }
        }
    }

    // ---- Outline and the regions that are not in it ----

    private fun blockName(block: OutlineBlock): String = when (block) {
        OutlineBlock.HISTORY -> texts.region(WindowRegion.HISTORY)
        OutlineBlock.LANGUAGES -> texts.region(WindowRegion.LANGUAGES)
        OutlineBlock.SERVICES -> texts.region(WindowRegion.SERVICES)
        OutlineBlock.INPUT -> localizationManager.getString("settings_window.layout_block_input")
        OutlineBlock.RESULT -> localizationManager.getString("settings_window.layout_block_result")
        OutlineBlock.BOARD -> localizationManager.getString("settings_window.layout_block_board")
        OutlineBlock.SECOND_RESULT -> texts.secondResultTitle()
        OutlineBlock.STATUS_BAR -> localizationManager.getString("settings_window.layout_block_status")
    }

    private fun currentLayout(): WindowLayout = store.state.value.workingConfiguration.windowLayout

    /** The menu a click on [block] opens, built against the draft as it is now. */
    internal fun popupFor(block: OutlineBlock): JPopupMenu {
        val layout = currentLayout()
        val orientation = componentOrientation
        val region = block.region
        return when {
            region != null -> RegionMenu(
                region = region,
                layout = layout,
                strings = localizationManager.regionMenuStrings(),
                onChange = ::writeWhole,
                effectivePanes = shownPanes,
                onSaveProfile = ::saveCurrent,
            ).popupMenu(orientation)

            block == OutlineBlock.SECOND_RESULT -> SecondResultMenu(
                layout = layout,
                effectivePanes = shownPanes,
                extraOutputType = secondResultType,
                strings = localizationManager.secondResultMenuStrings(),
                onChange = ::writeWhole,
            ).popupMenu(orientation)

            else -> JPopupMenu().apply {
                add(JCheckBoxMenuItem(localizationManager.getString("settings_window.layout_row_status"), layout.showStatusBar).apply {
                    name = STATUS_BAR_ITEM
                    addActionListener { writeWhole(layout.copy(showStatusBar = isSelected)) }
                })
                if (orientation != ComponentOrientation.UNKNOWN) applyComponentOrientation(orientation)
            }
        }
    }

    private fun present(popup: JPopupMenu, invoker: JComponent) = presenter(popup, invoker)

    /** A region the window does not show, and where its commands are instead. */
    private data class NotShown(val block: OutlineBlock, val explanation: String)

    private fun notShown(layout: WindowLayout, panes: PaneLayout): List<NotShown> {
        val hosts = layout.requiredHosts(panes)
        fun say(key: String) = localizationManager.getString(key)
        return buildList {
            if (layout.history == RegionSlot.HIDDEN) {
                add(NotShown(OutlineBlock.HISTORY, say("settings_window.layout_hidden_history")))
            }
            when (hosts.getValue(WindowFeature.SERVICE_SWITCH)) {
                FeatureHost.ComparisonHeader -> add(NotShown(OutlineBlock.SERVICES, say("settings_window.layout_hidden_services_board")))
                FeatureHost.InputStrip -> add(NotShown(OutlineBlock.SERVICES, say("settings_window.layout_hidden_services")))
                else -> Unit
            }
            if (!layout.showStatusBar) {
                val key = if (hosts.getValue(WindowFeature.NOTIFICATIONS) == FeatureHost.HistoryRowEnd) {
                    "settings_window.layout_hidden_status_history"
                } else {
                    "settings_window.layout_hidden_status_input"
                }
                add(NotShown(OutlineBlock.STATUS_BAR, say(key)))
            }
        }
    }

    private fun rebuildOutline(layout: WindowLayout) {
        outline.show(outlinePlan(layout, shownPanes, secondResultType))

        hiddenList.removeAll()
        val missing = notShown(layout, shownPanes)
        if (missing.isNotEmpty()) {
            hiddenList.addRow(JLabel(localizationManager.getString("settings_window.layout_hidden_title")).apply {
                font = SettingsVisuals.subSectionTitleFont(font)
                border = BorderFactory.createEmptyBorder(UIScale.scale(8), 0, UIScale.scale(2), 0)
            })
            missing.forEach { entry ->
                hiddenList.addRow(JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
                    isOpaque = false
                    name = "hidden:${entry.block}"
                    add(JButton(blockName(entry.block)).apply {
                        putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS)
                        toolTipText = localizationManager.getString("settings_window.layout_outline_block_tip", blockName(entry.block))
                        addActionListener { present(popupFor(entry.block), this) }
                    })
                    add(JLabel(entry.explanation).apply { foreground = SettingsVisuals.secondaryText() })
                })
            }
        }
        if (secondResultType == ExtraOutputType.None) {
            hiddenList.addRow(JLabel(localizationManager.getString("settings_window.layout_second_result_needs_type")).apply {
                font = SettingsVisuals.hintFont(font)
                foreground = SettingsVisuals.secondaryText()
                border = BorderFactory.createEmptyBorder(UIScale.scale(6), 0, 0, 0)
            })
        }
        hiddenList.revalidate()
        hiddenList.repaint()
        applyComponentOrientation(componentOrientation)
    }

    // ---- Starting points ----

    /** A starting point: its name, and what it changes. Selected only while applying it changes nothing. */
    private inner class Card(val preset: WindowPreset) : JToggleButton() {
        private val title = JLabel()
        private val badge = JLabel()
        private val hint = JLabel()
        private val hintText = WrappedText(hint, HINT_MEASURE)

        init {
            name = "card:$preset"
            layout = BorderLayout(0, UIScale.scale(4))
            // A bordered card whose selected state is a tint, so the labels inside stay readable.
            putClientProperty(
                FlatClientProperties.STYLE,
                "arc:12; selectedBackground:\$ToggleButton.toolbar.selectedBackground; selectedForeground:\$Label.foreground",
            )
            margin = Insets(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
            title.font = title.font.deriveFont(java.awt.Font.BOLD)
            badge.foreground = SettingsVisuals.accentMarker()
            hint.font = SettingsVisuals.hintFont(hint.font)
            hint.foreground = SettingsVisuals.secondaryText()
            hint.verticalAlignment = JLabel.TOP
            add(JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
                isOpaque = false
                add(title, BorderLayout.CENTER)
                add(badge, BorderLayout.LINE_END)
            }, BorderLayout.NORTH)
            add(hint, BorderLayout.CENTER)
            wrapped += hintText
            addActionListener { if (!isUpdatingFromState) choose() }
        }

        fun titleText() = localizationManager.getString(
            when (preset) {
                WindowPreset.MODERN_CLASSIC -> "settings_window.layout_card_modern_classic"
                WindowPreset.CLASSIC -> "settings_window.layout_card_classic"
                WindowPreset.COMPACT -> "settings_window.layout_card_compact"
                WindowPreset.FOCUS -> "settings_window.layout_card_focus"
            }
        )

        fun changesText() = localizationManager.getString(
            when (preset) {
                WindowPreset.MODERN_CLASSIC -> "settings_window.layout_card_modern_classic_changes"
                WindowPreset.CLASSIC -> "settings_window.layout_card_classic_changes"
                WindowPreset.COMPACT -> "settings_window.layout_card_compact_changes"
                WindowPreset.FOCUS -> "settings_window.layout_card_focus_changes"
            }
        )

        /** Whether applying this card to [layout] would change nothing. */
        fun isApplied(layout: WindowLayout) = preset.applyTo(layout) == layout

        fun refresh(layout: WindowLayout) {
            title.text = titleText()
            hintText.text = changesText()
            val applied = isApplied(layout)
            isSelected = applied
            badge.text = if (applied) localizationManager.getString("settings_window.layout_card_current") else ""
            toolTipText = changesText()
            getAccessibleContext().accessibleName = title.text
        }

        private fun choose() {
            writeLayout { preset.applyTo(it) }
            // A card that was already applied would otherwise stay deselected by the click itself.
            refresh(currentLayout())
        }
    }

    private fun cardGrid(): JPanel {
        val grid = JPanel(GridLayout(2, 2, UIScale.scale(8), UIScale.scale(8))).apply { isOpaque = false }
        WindowPreset.entries.forEach { preset ->
            val card = Card(preset)
            cards[preset] = card
            grid.add(card)
            registerSearchEntry(card.titleText(), card, hint = card.changesText())
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(grid, BorderLayout.CENTER)
        }
    }

    private fun currentName(layout: WindowLayout, profiles: List<LayoutProfile>): String {
        val card = cards.values.firstOrNull { it.isApplied(layout) }?.titleText()
        return card
            ?: profiles.profileMatching(layout)?.name
            ?: localizationManager.getString("settings_window.layout_current_custom")
    }

    // ---- Saved layouts ----

    private fun rebuildProfiles(layout: WindowLayout, profiles: List<LayoutProfile>) {
        profileList.removeAll()
        val current = profiles.profileMatching(layout)
        if (profiles.isEmpty()) {
            profileList.addRow(JLabel(localizationManager.getString("settings_window.layout_profiles_empty")).apply {
                foreground = SettingsVisuals.secondaryText()
            })
        }
        profiles.forEach { profile ->
            val isCurrent = profile === current
            profileList.addRow(JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
                isOpaque = false
                name = "profile:${profile.name}"
                border = BorderFactory.createEmptyBorder(UIScale.scale(2), 0, UIScale.scale(2), 0)
                add(JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(8), 0)).apply {
                    isOpaque = false
                    add(JLabel(profile.name))
                    if (isCurrent) {
                        add(JLabel(localizationManager.getString("settings_window.layout_profile_current")).apply {
                            name = PROFILE_CURRENT
                            foreground = SettingsVisuals.accentMarker()
                        })
                    }
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.TRAILING, UIScale.scale(4), 0)).apply {
                    isOpaque = false
                    add(JButton(localizationManager.getString("common.apply")).apply {
                        name = APPLY
                        isEnabled = !isCurrent
                        toolTipText = if (isCurrent) localizationManager.getString("settings_window.layout_profile_in_use") else null
                        addActionListener { applyProfile(profile.name) }
                    })
                    add(JButton(localizationManager.getString("common.rename")).apply {
                        name = RENAME
                        addActionListener { renameProfile(profile.name) }
                    })
                    add(JButton(localizationManager.getString("common.delete")).apply {
                        name = DELETE
                        addActionListener { deleteProfile(profile.name) }
                    })
                }, BorderLayout.LINE_END)
            })
        }
        val full = profiles.isFull
        saveButton.isEnabled = !full
        val reason = if (full) localizationManager.getString("settings_window.layout_profiles_full", LayoutProfile.MAX_PROFILES) else ""
        saveButton.toolTipText = reason.ifEmpty { null }
        saveHint.text = reason
        saveHint.isVisible = full
        profileList.revalidate()
        profileList.repaint()
        applyComponentOrientation(componentOrientation)
    }

    private fun profiles(): List<LayoutProfile> = store.state.value.workingConfiguration.layoutProfiles

    /** Saves the draft window as a named layout, asking for the name like the window's own menu does. */
    internal fun saveCurrent() {
        LayoutProfileSaver(
            prompts = prompts,
            profiles = ::profiles,
            layout = ::currentLayout,
            save = ::writeProfiles,
        ).saveCurrent()
    }

    internal fun applyProfile(name: String) {
        profiles().profileNamed(name)?.let { writeWhole(it.layout) }
    }

    internal fun renameProfile(name: String) {
        val existing = profiles().profileNamed(name) ?: return
        val typed = cleanProfileName(prompts.askRename(existing.name)) ?: return
        if (typed == existing.name) return
        val renamed = profiles().withProfileRenamed(existing.name, typed)
        if (renamed == null) prompts.showNameTaken(typed) else writeProfiles(renamed)
    }

    internal fun deleteProfile(name: String) {
        val existing = profiles().profileNamed(name) ?: return
        if (prompts.confirmDelete(existing.name)) writeProfiles(profiles().withProfileRemoved(existing.name))
    }

    // ---- Writes: the only two places this page changes the configuration ----

    private fun writeWhole(layout: WindowLayout) {
        applyDraft(store) { configuration: Configuration -> configuration.withWindowLayout(layout) }
    }

    private fun writeLayout(transform: (WindowLayout) -> WindowLayout) {
        applyDraft(store) { configuration -> configuration.withWindowLayout(transform(configuration.windowLayout)) }
    }

    private fun writeProfiles(profiles: List<LayoutProfile>) {
        applyDraft(store) { configuration -> configuration.withLayoutProfiles(profiles) }
    }

    // ---- Rendering ----

    override fun render(state: SettingsState) {
        val configuration = state.workingConfiguration
        val layout = configuration.windowLayout
        val translatorIds = availableTranslatorIds()
        comparisonAvailable = configuration.isComparisonEligible(translatorIds)

        shownLayout = layout
        shownPanes = configuration.effectivePaneLayout(translatorIds)
        secondResultType = configuration.extraOutputType
        shownProfiles = configuration.layoutProfiles

        withoutTrigger {
            syncPanes()
            cards.values.forEach { it.refresh(layout) }
            currentLabel.text = localizationManager.getString(
                "settings_window.layout_current_format",
                currentName(layout, shownProfiles),
            )
            val signature = listOf(layout, shownPanes, secondResultType, shownProfiles)
            if (signature != shownSignature) {
                shownSignature = signature
                rebuildOutline(layout)
                rebuildProfiles(layout, shownProfiles)
            }
        }
    }

    /** A label whose text is broken into lines at a fixed measure, in the page's own direction. */
    private inner class WrappedText(private val label: JLabel, private val measure: Int) {
        var text: String = ""
            set(value) {
                field = value
                rewrap()
            }

        fun rewrap() {
            val lines = wrapToMeasure(text, label.font, UIScale.scale(measure))
            label.text = textHtml(lines)
        }
    }

    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        wrapped.forEach { it.rewrap() }
    }

    internal companion object {
        /** The width, before scaling, that a card's explanation is wrapped to. */
        const val HINT_MEASURE = 250

        const val STATUS_BAR_ITEM = "status_bar"
        const val PROFILE_CURRENT = "profile_current"
        const val APPLY = "profile_apply"
        const val RENAME = "profile_rename"
        const val DELETE = "profile_delete"
    }
}

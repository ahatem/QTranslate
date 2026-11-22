package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Density
import com.github.ahatem.qtranslate.core.settings.data.LayoutAvailability
import com.github.ahatem.qtranslate.core.settings.data.LayoutDisabledReason
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import com.github.ahatem.qtranslate.ui.swing.shared.layout.LayoutTexts
import java.awt.ComponentOrientation
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator

/** The words a [RegionMenu] shows. Values are named by functions so every host shares one vocabulary. */
data class RegionMenuStrings(
    /** The region's own name, for submenu titles. */
    val regionName: (WindowRegion) -> String,
    val moveTo: String,
    val slotName: (RegionSlot) -> String,
    /** The Hide item of a region; it also says where that region's commands go. */
    val hide: (WindowRegion) -> String,
    val shareRow: String,
    val selector: String,
    val widgetName: (ServiceWidget) -> String,
    val stripStyle: String,
    val appearanceName: (ServiceSelectorAppearance) -> String,
    val density: String,
    val densityName: (Density) -> String,
    val saveProfile: String,
    /** The sentence that says why a control is unavailable. */
    val reason: (LayoutDisabledReason) -> String,
)

/** The strings of the region menus in the current language. */
fun LocalizationManager.regionMenuStrings(): RegionMenuStrings {
    val texts = LayoutTexts(this)
    return RegionMenuStrings(
        regionName = texts::region,
        moveTo = getString("main_window_main_menu.region_move_to"),
        slotName = texts::slot,
        hide = { region ->
            when (region) {
                WindowRegion.HISTORY -> getString("main_window_main_menu.region_hide_history")
                WindowRegion.LANGUAGES -> getString("main_window_main_menu.region_hide_languages")
                WindowRegion.SERVICES -> getString("main_window_main_menu.region_hide_services")
            }
        },
        shareRow = getString("main_window_main_menu.region_share_row"),
        selector = getString("main_window_main_menu.region_selector"),
        widgetName = texts::widget,
        stripStyle = getString("main_window_main_menu.region_strip_style"),
        appearanceName = texts::appearance,
        density = getString("main_window_main_menu.region_density"),
        densityName = { density ->
            when (density) {
                Density.COMFORTABLE -> getString("main_window_main_menu.region_density_comfortable")
                Density.COMPACT -> getString("main_window_main_menu.region_density_compact")
            }
        },
        saveProfile = getString("main_window_main_menu.region_save_profile"),
        reason = texts::reason,
    )
}

/**
 * The menu that arranges one region of the window: where it goes, whether it shares a row, and,
 * for the service selector, which widget and strip style it uses.
 *
 * One builder serves every host. The window's row popups and the View menu show it as it is built
 * here, and a host that edits a draft (the Settings outline) passes its own [onChange]. The menu
 * never writes anything itself: each choice hands one whole [WindowLayout] to [onChange], which
 * stores it through `Configuration.withWindowLayout`.
 *
 * A control the rules forbid is disabled, says why in its tooltip, and is followed by a visible
 * line with the same reason, so the explanation does not depend on hovering.
 *
 * @param effectivePanes the pane arrangement actually built, which differs from [WindowLayout.panes]
 * while a requested Comparison has too few translators.
 * @param onSaveProfile when given, adds "Save layout as profile..." at the end.
 */
class RegionMenu(
    private val region: WindowRegion,
    layout: WindowLayout,
    private val strings: RegionMenuStrings,
    private val onChange: (WindowLayout) -> Unit,
    private val effectivePanes: PaneLayout = layout.panes,
    private val onSaveProfile: (() -> Unit)? = null,
) {
    /** The layout the menu was built for. A name that cannot be mistaken for a Swing property. */
    private val window: WindowLayout = layout

    /** The menu's entries, built fresh so each host owns its own components. */
    fun items(): List<JComponent> = buildList {
        // Each distinct reason is written out once; the tooltip of every control it disables repeats it.
        val explained = mutableSetOf<LayoutDisabledReason>()
        val services = window.servicesControlsAvailability(effectivePanes)
        val regionAvailability = if (region == WindowRegion.SERVICES) services else LayoutAvailability.Enabled

        add(heading(strings.moveTo))
        val position = ButtonGroup()
        listOf(RegionSlot.TOP, RegionSlot.ABOVE_RESULTS, RegionSlot.BOTTOM, RegionSlot.HIDDEN).forEach { slot ->
            val text = if (slot == RegionSlot.HIDDEN) strings.hide(region) else strings.slotName(slot)
            add(radio("slot:$slot", text, window.slotOf(region) == slot, regionAvailability, position) {
                window.withSlot(region, slot)
            })
        }
        addReason(regionAvailability, explained)

        add(JSeparator())
        val sharing = window.shareRowAvailability(region, effectivePanes)
        add(JCheckBoxMenuItem(strings.shareRow).apply {
            name = SHARE_ROWS
            isSelected = window.shareRows
            gate(this, sharing)
            addActionListener { onChange(window.copy(shareRows = isSelected)) }
        })
        addReason(sharing, explained)

        if (region == WindowRegion.SERVICES) {
            add(JSeparator())
            add(heading(strings.selector))
            val widgets = ButtonGroup()
            ServiceWidget.entries.forEach { widget ->
                add(radio("widget:$widget", strings.widgetName(widget), window.serviceWidget == widget, services, widgets) {
                    window.copy(serviceWidget = widget)
                })
            }
            addReason(services, explained)

            val appearance = window.appearanceAvailability(effectivePanes)
            add(heading(strings.stripStyle))
            val styles = ButtonGroup()
            ServiceSelectorAppearance.entries.forEach { style ->
                add(radio("appearance:$style", strings.appearanceName(style), window.stripAppearance == style, appearance, styles) {
                    window.copy(stripAppearance = style)
                })
            }
            addReason(appearance, explained)
        }

        add(JSeparator())
        add(heading(strings.density))
        val densities = ButtonGroup()
        Density.entries.forEach { density ->
            add(radio("density:$density", strings.densityName(density), window.density == density, LayoutAvailability.Enabled, densities) {
                window.copy(density = density)
            })
        }

        onSaveProfile?.let { save ->
            add(JSeparator())
            add(JMenuItem(strings.saveProfile).apply {
                name = SAVE_PROFILE
                addActionListener { save() }
            })
        }
    }

    /** The menu as a popup, for a host that shows it on a right-click. */
    fun popupMenu(orientation: ComponentOrientation = ComponentOrientation.UNKNOWN): JPopupMenu =
        JPopupMenu().apply {
            items().forEach { add(it) }
            if (orientation != ComponentOrientation.UNKNOWN) applyComponentOrientation(orientation)
        }

    /** The same menu as a submenu, for a host that lists regions side by side. */
    fun menu(title: String = strings.regionName(region)): JMenu =
        JMenu(title).apply {
            name = "region:$region"
            items().forEach { add(it) }
        }

    private fun heading(text: String) = JMenuItem(text).apply {
        name = HEADING
        isEnabled = false
    }

    private fun radio(
        name: String,
        text: String,
        selected: Boolean,
        availability: LayoutAvailability,
        group: ButtonGroup,
        change: () -> WindowLayout,
    ) = JRadioButtonMenuItem(text).apply {
        this.name = name
        isSelected = selected
        group.add(this)
        gate(this, availability)
        addActionListener {
            val next = change()
            if (next != window) onChange(next)
        }
    }

    /** Disables [item] when [availability] says so, and puts the reason in its tooltip. */
    private fun gate(item: JMenuItem, availability: LayoutAvailability) {
        val reason = availability.disabledReason
        item.isEnabled = reason == null
        item.toolTipText = reason?.let(strings.reason)
    }

    /** A visible line that says why the controls above it are unavailable; nothing when they are not. */
    private fun MutableList<JComponent>.addReason(
        availability: LayoutAvailability,
        explained: MutableSet<LayoutDisabledReason>,
    ) {
        val reason = availability.disabledReason ?: return
        if (!explained.add(reason)) return
        add(JMenuItem(strings.reason(reason)).apply {
            name = REASON
            isEnabled = false
        })
    }

    companion object {
        /** Names of the non-choice entries, so a host or a test can tell them from the controls. */
        const val HEADING = "heading"
        const val REASON = "reason"
        const val SHARE_ROWS = "share_rows"
        const val SAVE_PROFILE = "save_profile"
    }
}

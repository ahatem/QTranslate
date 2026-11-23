package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.LayoutDisabledReason
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.SecondResultPlacement
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.ui.swing.shared.layout.LayoutTexts
import java.awt.Component
import java.awt.ComponentOrientation
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem

/** The words a [SecondResultMenu] shows. */
data class SecondResultMenuStrings(
    /** The group's name: the submenu title and the heading of the popup. */
    val title: String,
    /** What [SecondResultPlacement] is called for the panes it will be built with. */
    val optionName: (SecondResultPlacement, PaneLayout) -> String,
    /** The sentence that says why a choice is unavailable. */
    val reason: (LayoutDisabledReason) -> String,
)

/** The strings of the second result menu in the current language. */
fun LocalizationManager.secondResultMenuStrings(): SecondResultMenuStrings {
    val texts = LayoutTexts(this)
    return SecondResultMenuStrings(
        title = texts.secondResultTitle(),
        optionName = texts::secondResult,
        reason = texts::reason,
    )
}

/**
 * Where the second result sits: one radio group with words that fit the panes, offered in the View
 * menu and on a right-click over the second result's own header.
 *
 * Built like [RegionMenu]: one builder for every host, the menu never writes anything itself, and
 * each choice hands one whole [WindowLayout] to [onChange], which stores it through
 * `Configuration.withWindowLayout`. The checked entry is the placement that is built, which differs
 * from the stored one where the panes cannot show it. A choice the rules forbid is disabled and
 * says why in its tooltip, and a visible line repeats the reason so it does not depend on hovering.
 */
class SecondResultMenu(
    layout: WindowLayout,
    private val effectivePanes: PaneLayout,
    private val extraOutputType: ExtraOutputType,
    private val strings: SecondResultMenuStrings,
    private val onChange: (WindowLayout) -> Unit,
) {
    /** The layout the menu was built for. */
    private val window: WindowLayout = layout

    /** The menu's entries, built fresh so each host owns its own components. */
    fun items(withHeading: Boolean = true): List<JComponent> = buildList {
        if (withHeading) {
            add(JMenuItem(strings.title).apply {
                name = HEADING
                isEnabled = false
            })
        }
        val shown = window.effectiveSecondResult(effectivePanes)
        val group = ButtonGroup()
        val explained = mutableSetOf<LayoutDisabledReason>()
        SecondResultPlacement.entries.forEach { option ->
            val availability = window.secondResultAvailability(option, effectivePanes, extraOutputType)
            add(JRadioButtonMenuItem(strings.optionName(option, effectivePanes)).apply {
                name = "$OPTION_PREFIX$option"
                isSelected = shown == option
                group.add(this)
                val reason = availability.disabledReason
                isEnabled = reason == null
                toolTipText = reason?.let(strings.reason)
                addActionListener {
                    val next = window.copy(secondResult = option)
                    if (next != window) onChange(next)
                }
            })
        }
        SecondResultPlacement.entries.forEach { option ->
            val reason = window.secondResultAvailability(option, effectivePanes, extraOutputType).disabledReason ?: return@forEach
            if (!explained.add(reason)) return@forEach
            add(JMenuItem(strings.reason(reason)).apply {
                name = REASON
                isEnabled = false
            })
        }
    }

    /** The menu as a popup, for a host that shows it on a right-click. */
    fun popupMenu(orientation: ComponentOrientation = ComponentOrientation.UNKNOWN): JPopupMenu =
        JPopupMenu().apply {
            items().forEach { add(it) }
            if (orientation != ComponentOrientation.UNKNOWN) applyComponentOrientation(orientation)
        }

    /** The same menu as a submenu, for the View menu. */
    fun menu(title: String = strings.title): JMenu =
        JMenu(title).apply {
            name = SUBMENU
            items(withHeading = false).forEach { add(it) }
        }

    companion object {
        /** Prefix of a choice's name, followed by the [SecondResultPlacement]. */
        const val OPTION_PREFIX = "second:"
        const val HEADING = "heading"
        const val REASON = "reason"
        const val SUBMENU = "second_result"
    }
}

/**
 * The popup of the second result's header, rebuilt from the window as it is now each time it opens.
 *
 * @param current what the window is now; null before the first render.
 */
class SecondResultPopup(
    private val current: () -> Current?,
    private val strings: () -> SecondResultMenuStrings,
    private val onChange: (WindowLayout) -> Unit,
) : JPopupMenu() {

    /** The arrangement a popup is built for. */
    data class Current(
        val layout: WindowLayout,
        val effectivePanes: PaneLayout,
        val extraOutputType: ExtraOutputType,
        val isRtl: Boolean,
    )

    /** Fills the popup for the window as it is now. */
    fun refresh() {
        removeAll()
        val now = current() ?: return
        SecondResultMenu(now.layout, now.effectivePanes, now.extraOutputType, strings(), onChange)
            .items().forEach { add(it) }
        applyComponentOrientation(
            if (now.isRtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
        )
    }

    // The size used to place the popup on screen is taken before it becomes visible, so the items
    // have to be current by then.
    override fun show(invoker: Component?, x: Int, y: Int) {
        refresh()
        super.show(invoker, x, y)
    }
}

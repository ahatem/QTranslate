package com.github.ahatem.qtranslate.ui.swing.shared.layout

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.LayoutDisabledReason
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.SecondResultPlacement
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion

/**
 * The words for the layout model's values, shared by every place that offers a layout choice
 * (the Settings page and the window's own menus) so a value is never named two ways.
 *
 * Core answers with enums and [LayoutDisabledReason]; this is the only place that turns them into
 * localized text.
 */
class LayoutTexts(private val localizer: LocalizationManager) {

    fun pane(pane: PaneLayout?): String = when (pane) {
        PaneLayout.STACKED -> localizer.getString("settings_window.layout_panes_stacked")
        PaneLayout.SIDE_BY_SIDE -> localizer.getString("settings_window.layout_panes_side_by_side")
        PaneLayout.COMPARISON -> localizer.getString("settings_window.layout_panes_comparison")
        null -> ""
    }

    fun slot(slot: RegionSlot?): String = when (slot) {
        RegionSlot.TOP -> localizer.getString("settings_window.layout_slot_top")
        RegionSlot.ABOVE_RESULTS -> localizer.getString("settings_window.layout_slot_above_results")
        RegionSlot.BOTTOM -> localizer.getString("settings_window.layout_slot_bottom")
        RegionSlot.HIDDEN -> localizer.getString("settings_window.layout_slot_hidden")
        null -> ""
    }

    fun widget(widget: ServiceWidget?): String = when (widget) {
        ServiceWidget.PICKER -> localizer.getString("settings_window.layout_selector_picker")
        ServiceWidget.STRIP -> localizer.getString("settings_window.layout_selector_bottom_strip")
        null -> ""
    }

    fun appearance(appearance: ServiceSelectorAppearance?): String = when (appearance) {
        ServiceSelectorAppearance.ICONS_ONLY -> localizer.getString("settings_window.layout_appearance_icons")
        ServiceSelectorAppearance.ICONS_AND_TEXT -> localizer.getString("settings_window.layout_appearance_both")
        ServiceSelectorAppearance.TEXT_ONLY -> localizer.getString("settings_window.layout_appearance_text")
        null -> ""
    }

    /** The sentence that says why a control is unavailable. */
    fun reason(reason: LayoutDisabledReason): String = when (reason) {
        LayoutDisabledReason.COMPARISON_OWNS_SERVICES ->
            localizer.getString("settings_window.layout_selector_absent")
        LayoutDisabledReason.SELECTOR_NEEDS_OWN_ROW ->
            localizer.getString("settings_window.layout_selector_own_row")
        LayoutDisabledReason.APPEARANCE_NEEDS_STRIP ->
            localizer.getString("settings_window.layout_appearance_needs_strip")
        LayoutDisabledReason.SECOND_RESULT_NEEDS_TYPE ->
            localizer.getString("settings_window.layout_second_result_needs_type")
        LayoutDisabledReason.OWN_COLUMN_NEEDS_SIDE_BY_SIDE ->
            localizer.getString("settings_window.layout_second_result_needs_side_by_side")
        LayoutDisabledReason.COMPARISON_KEEPS_SECOND_RESULT_BELOW ->
            localizer.getString("settings_window.layout_second_result_below_board_only")
    }

    /** What the second result's group is called. */
    fun secondResultTitle(): String = localizer.getString("settings_window.layout_row_second_result")

    /**
     * What [placement] is called for [panes]: the words say where the second result goes in the
     * arrangement it is chosen for, so the same value reads differently in each.
     */
    fun secondResult(placement: SecondResultPlacement, panes: PaneLayout): String = when (placement) {
        SecondResultPlacement.BELOW -> when (panes) {
            PaneLayout.STACKED -> localizer.getString("settings_window.layout_second_result_below_result")
            PaneLayout.SIDE_BY_SIDE -> localizer.getString("settings_window.layout_second_result_below_both")
            PaneLayout.COMPARISON -> localizer.getString("settings_window.layout_second_result_below_board")
        }
        SecondResultPlacement.WITH_RESULT -> when (panes) {
            PaneLayout.SIDE_BY_SIDE -> localizer.getString("settings_window.layout_second_result_under_result")
            else -> localizer.getString("settings_window.layout_second_result_beside_result")
        }
        SecondResultPlacement.OWN_COLUMN -> localizer.getString("settings_window.layout_second_result_third_column")
    }

    /** The region's name as the Settings page and the window's menus call it. */
    fun region(region: WindowRegion): String = when (region) {
        WindowRegion.HISTORY -> localizer.getString("settings_window.layout_row_history")
        WindowRegion.LANGUAGES -> localizer.getString("settings_window.layout_row_languages")
        WindowRegion.SERVICES -> localizer.getString("settings_window.service_selector_sub")
    }
}

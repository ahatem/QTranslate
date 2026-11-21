package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.Serializable

/**
 * The main window's arrangement, as four independent values.
 *
 * The stored settings used to describe the same window twice over: a layout id, a set of four
 * chrome booleans, a selector style and a selector appearance — values that overlapped, so some
 * of their combinations produced identical pixels while others produced none at all (a history
 * row hidden inside a layout that has no other history controls leaves no way back). Every one of
 * those combinations had to be rendered and understood.
 *
 * These four values do not overlap and leave nothing unreachable:
 *
 * - [paneLayout] is how the result panes sit, and nothing else.
 * - [toolbarStyle] is how much chrome sits above them. It replaces the separate history and
 *   language-bar switches, so "hidden history controls" is a *shape* of the toolbar rather than a
 *   flag that can strip a layout of the only controls it had.
 * - [serviceSelector] is which widget chooses the active service. It does not exist in
 *   [PaneLayout.COMPARISON], which switches the primary translator from the result headers.
 * - [showStatusBar] is the footer, and is honestly independent of all three.
 *
 * Fifteen shapes without the footer, thirty with it. See [LayoutGeometry].
 */
@Serializable
data class LayoutConfig(
    val paneLayout: PaneLayout = PaneLayout.STACKED,
    val toolbarStyle: ToolbarStyle = ToolbarStyle.FULL,
    val serviceSelector: ServiceSelector = ServiceSelector.PICKER,
    val showStatusBar: Boolean = true,
) {
    /**
     * Whether [serviceSelector] means anything here.
     *
     * A comparison board has no selector widget of its own, so the value is carried through
     * rather than erased: switching back to a stacked arrangement restores the user's choice
     * instead of silently resetting it to the picker.
     */
    val isServiceSelectorVisible: Boolean get() = paneLayout != PaneLayout.COMPARISON

    val geometry: LayoutGeometry
        get() = LayoutGeometry(
            paneLayout = paneLayout,
            toolbarStyle = toolbarStyle,
            serviceSelector = if (isServiceSelectorVisible) serviceSelector else null,
            showStatusBar = showStatusBar
        )

    /** The arrangement actually used at runtime, after eligibility is taken into account. */
    fun effectivePaneLayout(isComparisonEligible: Boolean): PaneLayout =
        if (paneLayout == PaneLayout.COMPARISON && !isComparisonEligible) PaneLayout.STACKED else paneLayout

    /** The preset card this configuration *is*, or null for a custom combination. */
    fun matchingPreset(): LayoutPreset? = LayoutPreset.entries.firstOrNull { it.matches(this) }

    companion object {
        val DEFAULT = LayoutConfig()
    }
}

/** How the result panes sit. */
@Serializable
enum class PaneLayout {
    /** Input above output, the arrangement QTranslate has always had. */
    STACKED,
    SIDE_BY_SIDE,
    COMPARISON;

    /** The persisted layout id this arrangement stands for. */
    val presetId: String
        get() = when (this) {
            STACKED -> LayoutPresetIds.CLASSIC
            SIDE_BY_SIDE -> LayoutPresetIds.SIDE_BY_SIDE
            COMPARISON -> LayoutPresetIds.COMPARISON
        }

    companion object {
        /**
         * The arrangement a stored layout id stands for. Retired and unrecognised ids mean
         * STACKED, which is the same substitution [LayoutPresetIds.resolve] makes.
         */
        fun fromPresetId(id: String): PaneLayout = when (LayoutPresetIds.resolve(id)) {
            LayoutPresetIds.SIDE_BY_SIDE -> SIDE_BY_SIDE
            LayoutPresetIds.COMPARISON -> COMPARISON
            else -> STACKED
        }
    }
}

/** How much chrome sits above the panes. */
@Serializable
enum class ToolbarStyle {
    /** A language row plus the classic QTranslate history crumb row, each in its own row. */
    FULL,

    /** One combined row: the language controls and the history controls share it. */
    COMPACT,

    /** The language row alone. History stays reachable from its hotkey and the menu. */
    MINIMAL
}

/** Which widget chooses the active service. */
@Serializable
enum class ServiceSelector {
    /** A labelled picker in the toolbar, one per service role. */
    PICKER,

    /** The classic bottom strip of adjacent service tabs. */
    BOTTOM_STRIP
}

/**
 * The pixels a [LayoutConfig] actually produces.
 *
 * Two configurations that differ only in a value this arrangement ignores — the selector under
 * [PaneLayout.COMPARISON] — are the same geometry, which is why the selector is nullable here.
 * Used by the tests that enumerate the whole space, and by the screenshot matrix.
 */
data class LayoutGeometry(
    val paneLayout: PaneLayout,
    val toolbarStyle: ToolbarStyle,
    val serviceSelector: ServiceSelector?,
    val showStatusBar: Boolean,
) {
    companion object {
        /** All 15 arrangements, or all 30 once the status bar is counted. */
        fun all(): List<LayoutGeometry> = PaneLayout.values().flatMap { pane ->
            val selectors: List<ServiceSelector?> =
                if (pane == PaneLayout.COMPARISON) listOf(null) else ServiceSelector.values().toList()
            ToolbarStyle.values().flatMap { style ->
                selectors.flatMap { selector ->
                    listOf(true, false).map { status ->
                        LayoutGeometry(pane, style, selector, status)
                    }
                }
            }
        }
    }
}

/**
 * The one-click starting points on the Settings page.
 *
 * A card is a *macro over the same four values*, not a stored mode: [config] is written when the
 * card is clicked, and the card is highlighted while the current values equal it exactly. There is
 * no preset id to keep in sync and no "modified" state to reset.
 *
 * Three of the four are fixed points of [LayoutConfigMigration.fromV10] — they are what the
 * corresponding legacy layout already looks like — so a user who never touches the Customize rows
 * still sees their own arrangement highlighted after upgrading. The exception is the legacy bottom
 * strip, which is one row away in Customize rather than a card of its own.
 */
enum class LayoutPreset(val config: LayoutConfig) {
    /** Today's default installation: stacked panes, the crumb row, the picker. */
    CLASSIC(LayoutConfig(PaneLayout.STACKED, ToolbarStyle.FULL, ServiceSelector.PICKER, showStatusBar = true)),

    /** The same panes with one combined row instead of two. */
    COMPACT(LayoutConfig(PaneLayout.STACKED, ToolbarStyle.COMPACT, ServiceSelector.PICKER, showStatusBar = true)),

    SIDE_BY_SIDE(LayoutConfig(PaneLayout.SIDE_BY_SIDE, ToolbarStyle.FULL, ServiceSelector.PICKER, showStatusBar = true)),

    /**
     * The older opening: the separate crumb row, as every comparison user already has it. The
     * selector is recorded but unused, so this card is matched in comparison regardless of it.
     */
    COMPARISON(LayoutConfig(PaneLayout.COMPARISON, ToolbarStyle.FULL, ServiceSelector.PICKER, showStatusBar = true));

    /**
     * True when [candidate] is exactly this card, ignoring the values its arrangement cannot show.
     *
     * A comparison board has no selector, so whatever is stored there — including a bottom strip
     * the user chose before switching arrangements — must not stop the card from matching.
     */
    fun matches(candidate: LayoutConfig): Boolean =
        config.paneLayout == candidate.paneLayout &&
            config.toolbarStyle == candidate.toolbarStyle &&
            config.showStatusBar == candidate.showStatusBar &&
            (!candidate.isServiceSelectorVisible || config.serviceSelector == candidate.serviceSelector)
}

/**
 * The single v10 → v11 mapping, in one place and with no I/O.
 *
 * Deliberately pure and *not* wired into [ConfigMigrator] until the new settings page exists, so
 * the old page keeps writing the old fields in the meantime. The centre of this file is the
 * mapping; see `LayoutConfigMigrationTest` for the exhaustive proof that it is total.
 */
object LayoutConfigMigration {
    fun fromV10(config: Configuration): LayoutConfig =
        fromV10(config.layoutPresetId, config.toolbarVisibility, config.serviceSelectorStyle)

    fun fromV10(
        layoutPresetId: String,
        toolbarVisibility: ToolbarVisibility,
        serviceSelectorStyle: ServiceSelectorStyle
    ): LayoutConfig = LayoutConfig(
        paneLayout = PaneLayout.fromPresetId(layoutPresetId),
        // History first: someone who turned the history row off asked for less chrome than anyone
        // who only turned off the language bar, so they must not land on a style that brings a
        // history row back.
        toolbarStyle = when {
            !toolbarVisibility.isHistoryBarVisible -> ToolbarStyle.MINIMAL
            // The language row is the one row every style keeps, so hiding it can only mean
            // "give me the slimmest legal row" rather than "two rows, one of them empty".
            !toolbarVisibility.isLanguageBarVisible -> ToolbarStyle.COMPACT
            else -> ToolbarStyle.FULL
        },
        // The strip needs both the classic style and the panel it lives in. A classic selector
        // with the panel hidden was showing nothing at all, so it becomes a picker: the active
        // service has to be visible somewhere.
        serviceSelector = if (
            serviceSelectorStyle == ServiceSelectorStyle.CLASSIC && toolbarVisibility.isServicesPanelVisible
        ) {
            ServiceSelector.BOTTOM_STRIP
        } else {
            ServiceSelector.PICKER
        },
        showStatusBar = toolbarVisibility.isStatusBarVisible
    )
}

/**
 * This configuration's four layout values, derived from the older settings.
 *
 * The pre-cutover derivation: it exists so the model can be proved equivalent to the rule it
 * replaces while the window is still built from [Configuration.layoutPresetId] and the chrome
 * booleans. Once the settings page writes [Configuration.layoutConfig] and the renderers read it,
 * this is deleted rather than kept as a second truth.
 */
fun Configuration.legacyLayoutConfig(): LayoutConfig = LayoutConfigMigration.fromV10(this)

/**
 * Writes [layout] into this configuration, keeping the older layout fields in step with it.
 *
 * The inverse of [LayoutConfigMigration.fromV10], and temporary for the same reason: until the
 * renderers read the stored value, the window is still built from the old fields, so a page that
 * wrote only the new one would appear to do nothing. When the old fields go away this collapses to
 * a plain `copy(layoutConfig = layout)`.
 *
 * One value cannot survive the trip: [ToolbarStyle.COMPACT] means a single combined row, and the
 * old switches can only say "no separate history row", which is also what [ToolbarStyle.MINIMAL]
 * says. So COMPACT comes back as MINIMAL until the combined row exists. That is exactly why the
 * renderer and this bridge have to be replaced together.
 */
fun Configuration.withLayoutConfig(layout: LayoutConfig): Configuration = copy(
    layoutConfig = layout,
    layoutPresetId = layout.paneLayout.presetId,
    toolbarVisibility = ToolbarVisibility(
        isHistoryBarVisible = layout.toolbarStyle == ToolbarStyle.FULL,
        // The language row is the one row every style keeps: no style may hide the active pair.
        isLanguageBarVisible = true,
        isServicesPanelVisible = layout.isServiceSelectorVisible,
        isStatusBarVisible = layout.showStatusBar,
    ),
    serviceSelectorStyle = when (layout.serviceSelector) {
        ServiceSelector.PICKER -> ServiceSelectorStyle.ENHANCED
        ServiceSelector.BOTTOM_STRIP -> ServiceSelectorStyle.CLASSIC
    },
)

/** The arrangement to build, once comparison eligibility is taken into account. */
fun Configuration.effectivePaneLayout(translatorIds: List<String>): PaneLayout =
    legacyLayoutConfig().effectivePaneLayout(isComparisonEligible(translatorIds))

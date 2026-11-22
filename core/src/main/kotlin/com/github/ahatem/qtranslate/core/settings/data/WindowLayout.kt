package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.Serializable

/**
 * The main window as three regions, each placed in a slot.
 *
 * A slot means the same place in every pane layout (see [RegionSlot]), so one value describes the
 * window whatever the panes look like. Every function here is pure and has no knowledge of Swing or
 * of localized text: a rule that forbids a choice answers with a [LayoutDisabledReason], and the UI
 * turns that into words.
 *
 * This is the single source of truth for what the window contains. [rowPlan] says which rows exist
 * and what is in them, and [requiredHosts] says where every feature must be reachable; a renderer
 * follows the first and a test checks the real component tree against the second.
 *
 * Stored as [Configuration.windowLayout] and written only through [withWindowLayout].
 */
@Serializable
data class WindowLayout(
    val panes: PaneLayout = PaneLayout.STACKED,
    /** Back/forward, the provider crumb, image and document translate. */
    val history: RegionSlot = RegionSlot.TOP,
    /** Source, swap, target and Translate. */
    val languages: RegionSlot = RegionSlot.ABOVE_RESULTS,
    /** The service selector. */
    val services: RegionSlot = RegionSlot.BOTTOM,
    val serviceWidget: ServiceWidget = ServiceWidget.PICKER,
    /** Only meaningful for [ServiceWidget.STRIP]; stored either way so switching back restores it. */
    val stripAppearance: ServiceSelectorAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT,
    /** Regions in the same slot share one row (true) or each get their own (false). */
    val shareRows: Boolean = false,
    val showStatusBar: Boolean = true,
    val density: Density = Density.COMFORTABLE,
) {
    /** The slot [region] is placed in. */
    fun slotOf(region: WindowRegion): RegionSlot = when (region) {
        WindowRegion.HISTORY -> history
        WindowRegion.LANGUAGES -> languages
        WindowRegion.SERVICES -> services
    }

    /** This layout with [region] moved to [slot]. */
    fun withSlot(region: WindowRegion, slot: RegionSlot): WindowLayout = when (region) {
        WindowRegion.HISTORY -> copy(history = slot)
        WindowRegion.LANGUAGES -> copy(languages = slot)
        WindowRegion.SERVICES -> copy(services = slot)
    }

    /**
     * The pane arrangement actually built: a requested Comparison without enough usable
     * translators renders Stacked, and the stored preference is not rewritten.
     */
    fun effectivePanes(isComparisonEligible: Boolean): PaneLayout =
        if (panes == PaneLayout.COMPARISON && !isComparisonEligible) PaneLayout.STACKED else panes

    // ---- Rules (section 1 of the layout plan) ----

    /**
     * Whether the controls that write [services] and [serviceWidget] do anything.
     *
     * Under Comparison the board header switches the primary translator, so both values stay
     * stored (leaving Comparison restores them) but cannot be changed meanwhile.
     */
    fun servicesControlsAvailability(effectivePanes: PaneLayout): LayoutAvailability =
        if (effectivePanes == PaneLayout.COMPARISON) {
            LayoutAvailability.Disabled(LayoutDisabledReason.COMPARISON_OWNS_SERVICES)
        } else {
            LayoutAvailability.Enabled
        }

    /**
     * Whether [shareRows] can put [region] in a row with its neighbours.
     *
     * The strip stretches its tabs across the full width, so it never shares a row. Under
     * Comparison the services region does not exist at all.
     */
    fun shareRowAvailability(region: WindowRegion, effectivePanes: PaneLayout): LayoutAvailability = when {
        region != WindowRegion.SERVICES -> LayoutAvailability.Enabled
        effectivePanes == PaneLayout.COMPARISON ->
            LayoutAvailability.Disabled(LayoutDisabledReason.COMPARISON_OWNS_SERVICES)
        serviceWidget == ServiceWidget.STRIP ->
            LayoutAvailability.Disabled(LayoutDisabledReason.STRIP_NEEDS_OWN_ROW)
        else -> LayoutAvailability.Enabled
    }

    /** Whether [stripAppearance] applies: only to the strip, and never under Comparison. */
    fun appearanceAvailability(effectivePanes: PaneLayout): LayoutAvailability = when {
        effectivePanes == PaneLayout.COMPARISON ->
            LayoutAvailability.Disabled(LayoutDisabledReason.COMPARISON_OWNS_SERVICES)
        serviceWidget != ServiceWidget.STRIP ->
            LayoutAvailability.Disabled(LayoutDisabledReason.APPEARANCE_NEEDS_STRIP)
        else -> LayoutAvailability.Enabled
    }

    // ---- Rows ----

    /**
     * Which rows each visible slot holds, in order, for [effectivePanes].
     *
     * Every slot except [RegionSlot.HIDDEN] is present, in the order [RegionSlot.TOP],
     * [RegionSlot.ABOVE_RESULTS], [RegionSlot.BOTTOM], with an empty list when it holds nothing.
     * Within a slot the regions run in the fixed order [WindowRegion.entries] (history, languages,
     * services), and a row is an ordered list of regions that share it.
     *
     * - A hidden region is absent.
     * - Under Comparison the services region is absent: the board header owns switching.
     * - With [shareRows] the regions of a slot share one row, except a [ServiceWidget.STRIP]
     *   services region, which is always its own row.
     */
    fun rowPlan(effectivePanes: PaneLayout): Map<RegionSlot, List<List<WindowRegion>>> {
        val present = WindowRegion.entries.filter { region ->
            slotOf(region) != RegionSlot.HIDDEN &&
                !(region == WindowRegion.SERVICES && effectivePanes == PaneLayout.COMPARISON)
        }
        return RegionSlot.entries.filter { it != RegionSlot.HIDDEN }.associateWith { slot ->
            val inSlot = present.filter { slotOf(it) == slot }
            if (!shareRows) {
                inSlot.map { listOf(it) }
            } else {
                val ownRow = inSlot.filter { it.alwaysOwnRow() }
                val shared = inSlot.filterNot { it.alwaysOwnRow() }
                buildList {
                    if (shared.isNotEmpty()) add(shared)
                    ownRow.forEach { add(listOf(it)) }
                }
            }
        }
    }

    private fun WindowRegion.alwaysOwnRow(): Boolean =
        this == WindowRegion.SERVICES && serviceWidget == ServiceWidget.STRIP

    // ---- Routes ----

    /**
     * Where each [WindowFeature] must be reachable, so hiding a region never strands a command.
     *
     * A feature lives in its own region's row while that region is shown, and otherwise falls
     * back as described on [FeatureHost].
     */
    fun requiredHosts(effectivePanes: PaneLayout): Map<WindowFeature, FeatureHost> {
        val historyHost =
            if (history != RegionSlot.HIDDEN) FeatureHost.RegionRow(WindowRegion.HISTORY) else FeatureHost.InputStrip
        val languagesHost =
            if (languages != RegionSlot.HIDDEN) FeatureHost.RegionRow(WindowRegion.LANGUAGES) else FeatureHost.InputStrip
        val servicesHost = when {
            effectivePanes == PaneLayout.COMPARISON -> FeatureHost.ComparisonHeader
            services != RegionSlot.HIDDEN -> FeatureHost.RegionRow(WindowRegion.SERVICES)
            else -> FeatureHost.InputStrip
        }
        val notificationsHost = when {
            showStatusBar -> FeatureHost.StatusBar
            history != RegionSlot.HIDDEN -> FeatureHost.HistoryRowEnd
            else -> FeatureHost.InputStrip
        }
        return mapOf(
            WindowFeature.BACK_FORWARD to historyHost,
            WindowFeature.IMAGE_AND_DOCUMENT to historyHost,
            WindowFeature.LANGUAGE_PAIR to languagesHost,
            WindowFeature.TRANSLATE_ACTION to languagesHost,
            WindowFeature.SERVICE_SWITCH to servicesHost,
            WindowFeature.NOTIFICATIONS to notificationsHost,
        )
    }

    companion object {
        /** "Modern classic": the v1.5.0 window. New installs and "Reset layout" get this. */
        val DEFAULT = WindowLayout()
    }
}

/** How the result panes sit. */
@Serializable
enum class PaneLayout {
    /** Input above output, the arrangement QTranslate has always had. */
    STACKED,
    SIDE_BY_SIDE,
    COMPARISON;

    /** The layout id this arrangement stands for, as the v10 settings and the menu name it. */
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

/**
 * Where a region sits. The same slot means the same place in every pane layout:
 *
 * | slot | Stacked | Side by side | Comparison |
 * |---|---|---|---|
 * | [TOP] | above the input pane | above both columns | above the input pane |
 * | [ABOVE_RESULTS] | between input and output | above the output column only | above the board |
 * | [BOTTOM] | below the panes, above the status bar | same | same |
 * | [HIDDEN] | not drawn; its commands fall back (see [FeatureHost]) | same | same |
 */
@Serializable
enum class RegionSlot { TOP, ABOVE_RESULTS, BOTTOM, HIDDEN }

/** Which widget chooses the active service. */
@Serializable
enum class ServiceWidget { PICKER, STRIP }

/** Spacing of the chrome rows. */
@Serializable
enum class Density { COMFORTABLE, COMPACT }

/** A region of the window that can be placed in a slot. Declaration order is the order within a slot. */
enum class WindowRegion { HISTORY, LANGUAGES, SERVICES }

/** Why a layout control is unavailable. Core does not know the words; the UI maps each to a sentence. */
enum class LayoutDisabledReason {
    /** Comparison's board header switches the primary translator, so the services region does not exist. */
    COMPARISON_OWNS_SERVICES,

    /** The strip stretches across the full width, so it cannot share a row. */
    STRIP_NEEDS_OWN_ROW,

    /** Appearance applies to the strip only. */
    APPEARANCE_NEEDS_STRIP,
}

/** Whether a layout control can be used, and if not, why. */
sealed interface LayoutAvailability {
    data object Enabled : LayoutAvailability
    data class Disabled(val reason: LayoutDisabledReason) : LayoutAvailability

    val isEnabled: Boolean get() = this is Enabled

    /** The reason this is disabled, or null when it is enabled. */
    val disabledReason: LayoutDisabledReason? get() = (this as? Disabled)?.reason
}

/** A command that must stay reachable whatever the arrangement. */
enum class WindowFeature {
    BACK_FORWARD,
    IMAGE_AND_DOCUMENT,
    LANGUAGE_PAIR,
    TRANSLATE_ACTION,
    SERVICE_SWITCH,
    NOTIFICATIONS,
}

/** A visible place a [WindowFeature] is hosted. */
sealed interface FeatureHost {
    /** In the row of [region], which is placed in a visible slot. */
    data class RegionRow(val region: WindowRegion) : FeatureHost

    /** The action strip of the input pane: where commands go when their own region is hidden. */
    data object InputStrip : FeatureHost

    /** The board header of the Comparison layout, which owns service switching there. */
    data object ComparisonHeader : FeatureHost

    /** The status bar footer, hosting the notification button. */
    data object StatusBar : FeatureHost

    /** The end of the history row, which hosts the notification button when the status bar is off. */
    data object HistoryRowEnd : FeatureHost
}

/**
 * A starting point on the Settings page, written as a partial recipe: a card names only the values
 * it is about and leaves everything else as the user had it.
 */
data class WindowRecipe(
    val panes: PaneLayout? = null,
    val history: RegionSlot? = null,
    val languages: RegionSlot? = null,
    val services: RegionSlot? = null,
    val serviceWidget: ServiceWidget? = null,
    val stripAppearance: ServiceSelectorAppearance? = null,
    val shareRows: Boolean? = null,
    val showStatusBar: Boolean? = null,
    val density: Density? = null,
) {
    fun applyTo(base: WindowLayout): WindowLayout = base.copy(
        panes = panes ?: base.panes,
        history = history ?: base.history,
        languages = languages ?: base.languages,
        services = services ?: base.services,
        serviceWidget = serviceWidget ?: base.serviceWidget,
        stripAppearance = stripAppearance ?: base.stripAppearance,
        shareRows = shareRows ?: base.shareRows,
        showStatusBar = showStatusBar ?: base.showStatusBar,
        density = density ?: base.density,
    )
}

/** The starting-point cards. [MODERN_CLASSIC] writes exactly [WindowLayout.DEFAULT] (bar the strip appearance). */
enum class WindowPreset(val recipe: WindowRecipe) {
    /** The v1.5.0 window: stacked panes, history on top, languages between the panes, picker at the bottom. */
    MODERN_CLASSIC(
        WindowRecipe(
            panes = PaneLayout.STACKED,
            history = RegionSlot.TOP,
            languages = RegionSlot.ABOVE_RESULTS,
            services = RegionSlot.BOTTOM,
            serviceWidget = ServiceWidget.PICKER,
            shareRows = false,
            showStatusBar = true,
            density = Density.COMFORTABLE,
        )
    ),

    /** QTranslate 6: a bottom strip of service tabs and dense spacing. */
    CLASSIC(
        WindowRecipe(
            panes = PaneLayout.STACKED,
            history = RegionSlot.TOP,
            languages = RegionSlot.ABOVE_RESULTS,
            services = RegionSlot.BOTTOM,
            serviceWidget = ServiceWidget.STRIP,
            stripAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT,
            shareRows = false,
            showStatusBar = true,
            density = Density.COMPACT,
        )
    ),

    /** History and languages share one row at the top. */
    COMPACT(
        WindowRecipe(
            history = RegionSlot.TOP,
            languages = RegionSlot.TOP,
            shareRows = true,
        )
    ),

    /** As [COMPACT], with the service selector reduced to its chip, no status bar and dense spacing. */
    FOCUS(
        WindowRecipe(
            history = RegionSlot.TOP,
            languages = RegionSlot.TOP,
            services = RegionSlot.HIDDEN,
            shareRows = true,
            showStatusBar = false,
            density = Density.COMPACT,
        )
    );

    fun applyTo(base: WindowLayout): WindowLayout = recipe.applyTo(base)
}

/** A named, saved layout. Names are typed by the user and need no translation. */
@Serializable
data class LayoutProfile(val name: String, val layout: WindowLayout) {
    companion object {
        /** The most profiles a user can keep. */
        const val MAX_PROFILES = 20
    }
}

/**
 * The v10 settings expressed as a [WindowLayout]: a pure mapping with no I/O.
 *
 * Existing users see the window they already had. [ConfigMigrator] runs it for every stored
 * v10, v11 and v12 configuration, reading the v10 fields, which nothing writes any more.
 */
object WindowLayoutMigration {
    fun fromV10(config: Configuration): WindowLayout = fromV10(
        config.layoutPresetId,
        config.toolbarVisibility,
        config.serviceSelectorStyle,
        config.serviceSelectorAppearance,
    )

    fun fromV10(
        layoutPresetId: String,
        toolbarVisibility: ToolbarVisibility,
        serviceSelectorStyle: ServiceSelectorStyle,
        serviceSelectorAppearance: ServiceSelectorAppearance,
    ): WindowLayout {
        val panes = PaneLayout.fromPresetId(layoutPresetId)
        return WindowLayout(
            panes = panes,
            history = if (toolbarVisibility.isHistoryBarVisible) RegionSlot.TOP else RegionSlot.HIDDEN,
            // The language row sat between the panes in Stacked and Comparison, but above both
            // columns in Side by side.
            languages = when {
                !toolbarVisibility.isLanguageBarVisible -> RegionSlot.HIDDEN
                panes == PaneLayout.SIDE_BY_SIDE -> RegionSlot.TOP
                else -> RegionSlot.ABOVE_RESULTS
            },
            services = if (toolbarVisibility.isServicesPanelVisible) RegionSlot.BOTTOM else RegionSlot.HIDDEN,
            serviceWidget = when (serviceSelectorStyle) {
                ServiceSelectorStyle.CLASSIC -> ServiceWidget.STRIP
                ServiceSelectorStyle.ENHANCED -> ServiceWidget.PICKER
            },
            stripAppearance = serviceSelectorAppearance,
            shareRows = false,
            showStatusBar = toolbarVisibility.isStatusBarVisible,
            density = Density.COMFORTABLE,
        )
    }
}

/** The one write path for the window's arrangement. */
fun Configuration.withWindowLayout(layout: WindowLayout): Configuration = copy(windowLayout = layout)

/** Stores [profiles], keeping at most [LayoutProfile.MAX_PROFILES] of them. */
fun Configuration.withLayoutProfiles(profiles: List<LayoutProfile>): Configuration =
    copy(layoutProfiles = profiles.take(LayoutProfile.MAX_PROFILES))

/** The pane arrangement to build, once comparison eligibility is taken into account. */
fun Configuration.effectivePaneLayout(translatorIds: List<String>): PaneLayout =
    windowLayout.effectivePanes(isComparisonEligible(translatorIds))

package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.Serializable

/**
 * The main window as independent *visual regions*, each with one meaningful choice.
 *
 * This supersedes [LayoutConfig]'s shared [ToolbarStyle]: toolbar style folded unrelated regions
 * into one enum, so expressing one preference forced another and some states silently ignored a
 * stored value. Here every region has its own value, and a choice that would strand a feature
 * moves that feature somewhere visible instead of hiding it. [featureRoutes] makes that promise
 * testable over the whole space.
 *
 * [LayoutConfig] stays stored as the v11 source until the following release: a field removed from
 * this data class would be discarded by `ignoreUnknownKeys` before a migration could read it.
 */
@Serializable
data class WorkspaceLayout(
    val panes: PaneLayout = PaneLayout.STACKED,
    val history: HistoryPlacement = HistoryPlacement.TOP_ROW,
    val languages: LanguagePlacement = LanguagePlacement.ROW,
    val services: ServicePlacement = ServicePlacement.PICKER,
    val appearance: ServiceSelectorAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT,
    val showStatusBar: Boolean = true,
) {
    companion object {
        val DEFAULT = WorkspaceLayout()
    }

    /**
     * The arrangement the window actually builds.
     *
     * History can only be inline with a row that exists. Asking for both inline history and a
     * relocated language row resolves to a top history row; the setting is not ignored — the
     * summary and the tooltip say what is shown, and [featureRoutes] still guarantees a route.
     */
    val effective: WorkspaceLayout
        get() = if (history == HistoryPlacement.COMBINED && languages == LanguagePlacement.RELOCATED) {
            copy(history = HistoryPlacement.TOP_ROW)
        } else {
            this
        }

    /** The deterministic visible route to every feature in the effective arrangement. */
    fun featureRoutes(): Map<LayoutFeature, FeatureRoute> {
        val e = effective
        val historyRoute = when (e.history) {
            HistoryPlacement.TOP_ROW -> FeatureRoute.HISTORY_ROW_TOP
            HistoryPlacement.BOTTOM_ROW -> FeatureRoute.HISTORY_ROW_BOTTOM
            HistoryPlacement.COMBINED -> FeatureRoute.COMBINED_ROW
            HistoryPlacement.RELOCATED -> FeatureRoute.INPUT_PANE
        }
        val languageRoute = when (e.languages) {
            LanguagePlacement.ROW -> FeatureRoute.LANGUAGE_ROW
            LanguagePlacement.RELOCATED -> FeatureRoute.INPUT_PANE
        }
        val serviceRoute = when {
            e.panes == PaneLayout.COMPARISON -> FeatureRoute.COMPARISON_HEADER
            e.services == ServicePlacement.BOTTOM_STRIP -> FeatureRoute.BOTTOM_STRIP
            e.services == ServicePlacement.PICKER -> FeatureRoute.PICKER
            else -> FeatureRoute.VIEW_MENU
        }
        return mapOf(
            LayoutFeature.BACK_FORWARD to historyRoute,
            LayoutFeature.OCR_AND_DOCUMENT to historyRoute,
            LayoutFeature.LANGUAGE_PAIR to languageRoute,
            LayoutFeature.SERVICE_SWITCH to serviceRoute,
            LayoutFeature.STATUS_MESSAGES to if (e.showStatusBar) {
                FeatureRoute.STATUS_BAR
            } else {
                FeatureRoute.IN_WINDOW_NOTICE
            },
        )
    }
}

/** Where the history controls — back/forward, OCR and document — live. */
@Serializable
enum class HistoryPlacement {
    /** The classic QTranslate top row: back/forward arrows, the provider crumb, capture actions. */
    TOP_ROW,

    /** The same row below the panes. */
    BOTTOM_ROW,

    /** One shared row with the language controls. */
    COMBINED,

    /** No row: the controls move into the input pane. */
    RELOCATED,
}

/** Where the active source/target pair lives. */
@Serializable
enum class LanguagePlacement {
    ROW,
    RELOCATED,
}

/** How the active service is chosen. */
@Serializable
enum class ServicePlacement {
    PICKER,
    BOTTOM_STRIP,

    /** A service button in the language row / View menu, with the active service named. */
    RELOCATED,
}

/** A capability that must stay reachable whatever the arrangement. */
enum class LayoutFeature {
    BACK_FORWARD,
    OCR_AND_DOCUMENT,
    LANGUAGE_PAIR,
    SERVICE_SWITCH,
    STATUS_MESSAGES,
}

/** A visible place a [LayoutFeature] can be reached from. */
enum class FeatureRoute {
    HISTORY_ROW_TOP,
    HISTORY_ROW_BOTTOM,
    COMBINED_ROW,
    LANGUAGE_ROW,
    INPUT_PANE,
    PICKER,
    BOTTOM_STRIP,
    VIEW_MENU,
    COMPARISON_HEADER,
    STATUS_BAR,
    IN_WINDOW_NOTICE,
}

/**
 * A starting point on the settings page. A recipe names only the regions the card is about;
 * everything else keeps its current value, so "compact but keep my bottom strip" is one click.
 */
data class LayoutRecipe(
    val panes: PaneLayout? = null,
    val history: HistoryPlacement? = null,
    val languages: LanguagePlacement? = null,
    val services: ServicePlacement? = null,
    val appearance: ServiceSelectorAppearance? = null,
    val showStatusBar: Boolean? = null,
) {
    fun applyTo(base: WorkspaceLayout) = base.copy(
        panes = panes ?: base.panes,
        history = history ?: base.history,
        languages = languages ?: base.languages,
        services = services ?: base.services,
        appearance = appearance ?: base.appearance,
        showStatusBar = showStatusBar ?: base.showStatusBar,
    )
}

/** The four one-click starting points, as partial recipes rather than whole configurations. */
enum class WorkspacePreset(val recipe: LayoutRecipe) {
    /**
     * The classic QTranslate feel: back/forward arrows and the provider crumb in the top row,
     * the language row below, and the dense bottom tab strip with icon and text.
     */
    CLASSIC(
        LayoutRecipe(
            panes = PaneLayout.STACKED,
            history = HistoryPlacement.TOP_ROW,
            languages = LanguagePlacement.ROW,
            services = ServicePlacement.BOTTOM_STRIP,
            appearance = ServiceSelectorAppearance.ICONS_AND_TEXT,
            showStatusBar = true,
        )
    ),

    /** One combined top row; leaves panes, service choice, appearance and footer alone. */
    COMPACT(LayoutRecipe(history = HistoryPlacement.COMBINED, languages = LanguagePlacement.ROW)),

    /** Split panes; leaves all chrome alone. */
    SIDE_BY_SIDE(LayoutRecipe(panes = PaneLayout.SIDE_BY_SIDE)),

    /** The comparison board; leaves all chrome and the stored service choice alone. */
    COMPARISON(LayoutRecipe(panes = PaneLayout.COMPARISON)),
}

/** The single v11 → v12 mapping, pure and exhaustive over the current space. */
object WorkspaceLayoutMigration {
    fun fromV11(config: Configuration): WorkspaceLayout =
        fromV11(config.layoutConfig, config.serviceSelectorAppearance)

    fun fromV11(layout: LayoutConfig, appearance: ServiceSelectorAppearance): WorkspaceLayout =
        WorkspaceLayout(
            panes = layout.paneLayout,
            // MINIMAL used to hide the history bar with its OCR/document buttons; the new model
            // relocates those controls instead, so a feature never disappears with its row.
            history = when (layout.toolbarStyle) {
                ToolbarStyle.FULL -> HistoryPlacement.TOP_ROW
                ToolbarStyle.COMPACT -> HistoryPlacement.COMBINED
                ToolbarStyle.MINIMAL -> HistoryPlacement.RELOCATED
            },
            // The v11 renderer always showed the language row; keep that.
            languages = LanguagePlacement.ROW,
            services = when (layout.serviceSelector) {
                ServiceSelector.PICKER -> ServicePlacement.PICKER
                ServiceSelector.BOTTOM_STRIP -> ServicePlacement.BOTTOM_STRIP
            },
            // v11 dropped this from its mapping but never cleared the old field, so the user's
            // choice survives and can be restored here.
            appearance = appearance,
            showStatusBar = layout.showStatusBar,
        )

    /** All 30 v11 geometries × 3 appearances: the space a v11 file can hold. */
    fun allV11Input(): List<Pair<LayoutConfig, ServiceSelectorAppearance>> =
        LayoutGeometry.all().flatMap { geometry ->
            val layout = LayoutConfig(
                paneLayout = geometry.paneLayout,
                toolbarStyle = geometry.toolbarStyle,
                serviceSelector = geometry.serviceSelector ?: ServiceSelector.PICKER,
                showStatusBar = geometry.showStatusBar,
            )
            ServiceSelectorAppearance.entries.map { layout to it }
        }
}

/** The arrangement actually used at runtime, after comparison eligibility is taken into account. */
fun Configuration.effectiveWorkspaceLayout(translatorIds: List<String>): WorkspaceLayout {
    val resolved = workspaceLayout.effective
    return if (resolved.panes == PaneLayout.COMPARISON && !isComparisonEligible(translatorIds)) {
        resolved.copy(panes = PaneLayout.STACKED)
    } else {
        resolved
    }
}

/**
 * The one transform every layout write goes through. Keeps the older v11 fields in step so a
 * config never holds two descriptions that disagree; they are read by nothing once v12 ships.
 */
fun Configuration.withWorkspaceLayout(layout: WorkspaceLayout): Configuration = copy(
    workspaceLayout = layout,
    layoutConfig = LayoutConfig(
        paneLayout = layout.panes,
        toolbarStyle = when (layout.history) {
            HistoryPlacement.TOP_ROW, HistoryPlacement.BOTTOM_ROW -> ToolbarStyle.FULL
            HistoryPlacement.COMBINED -> ToolbarStyle.COMPACT
            HistoryPlacement.RELOCATED -> ToolbarStyle.MINIMAL
        },
        serviceSelector = when (layout.services) {
            ServicePlacement.PICKER, ServicePlacement.RELOCATED -> ServiceSelector.PICKER
            ServicePlacement.BOTTOM_STRIP -> ServiceSelector.BOTTOM_STRIP
        },
        showStatusBar = layout.showStatusBar,
    ),
    layoutPresetId = layout.panes.presetId,
    toolbarVisibility = ToolbarVisibility(
        isHistoryBarVisible = layout.history != HistoryPlacement.RELOCATED,
        isLanguageBarVisible = layout.languages == LanguagePlacement.ROW,
        isServicesPanelVisible = layout.services != ServicePlacement.RELOCATED &&
            layout.panes != PaneLayout.COMPARISON,
        isStatusBarVisible = layout.showStatusBar,
    ),
    serviceSelectorStyle = if (layout.services == ServicePlacement.BOTTOM_STRIP) {
        ServiceSelectorStyle.CLASSIC
    } else {
        ServiceSelectorStyle.ENHANCED
    },
    serviceSelectorAppearance = layout.appearance,
)

package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class ExtraOutputType {
    None, BackwardTranslate, Summarize, Rewrite
}

@Serializable
enum class ExtraOutputSource {
    Input, Output
}

/** Stable identifiers shared by persisted layout settings and orchestration policy. */
object LayoutPresetIds {
    const val CLASSIC = "classic"
    const val SIDE_BY_SIDE = "side_by_side"
    const val COMPARISON = "comparison"

    /** A retired layout. Older configurations may still carry it, so it is only ever read, never offered. */
    const val LEGACY_COMPACT = "compact"

    /** The layout that [id] stands for: [LEGACY_COMPACT] and unrecognised ids resolve to Classic. */
    fun resolve(id: String): String = when (id) {
        CLASSIC, SIDE_BY_SIDE, COMPARISON -> id
        else -> CLASSIC
    }
}

/** Replaces a retired layout id with its successor, so the next write persists the current one. */
fun Configuration.withoutRetiredLayout(): Configuration =
    if (layoutPresetId == LayoutPresetIds.LEGACY_COMPACT) {
        copy(layoutPresetId = LayoutPresetIds.resolve(layoutPresetId))
    } else {
        this
    }

/**
 * The complete request needed to compute one Extra Output result.
 *
 * This intentionally contains only Extra Output settings, so an immediate refresh does not
 * carry a snapshot of unrelated application configuration while the settings flow catches up.
 */
data class ExtraOutputRequest(
    val type: ExtraOutputType,
    val source: ExtraOutputSource,
    val summaryLength: String,
    val rewriteStyle: String,
) {
    companion object {
        fun from(config: Configuration) = ExtraOutputRequest(
            type = config.extraOutputType,
            source = config.extraOutputSource,
            summaryLength = config.summaryLength,
            rewriteStyle = config.rewriteStyle,
        )
    }
}

@Serializable
enum class TextSource {
    Input, Output, ExtraOutput
}

/** The action triggered after text is selected with the mouse in another application. */
@Serializable
enum class SelectionBehavior {
    /** Mouse-selection capture is disabled. */
    OFF,
    /** Show the floating translation button near the selection. */
    SHOW_ICON,
    /** Open or refresh Quick Translate and translate the selection. */
    TRANSLATE,
    /** Translate the selection and read the translated result aloud. */
    TRANSLATE_AND_READ
}

/** Which request-owned text is spoken for [SelectionBehavior.TRANSLATE_AND_READ]. */
@Serializable
enum class SelectionReadSource {
    SOURCE,
    TRANSLATION
}

/**
 * Controls which word is used for automatic dictionary lookups when a
 * single word is translated.
 *
 * [OFF]        — no automatic lookup; user searches manually.
 * [TRANSLATED] — looks up the translated (target-language) word.
 * [SOURCE]     — looks up the source (input) word.
 */
@Serializable
enum class DictionaryAutoSource {
    OFF,
    TRANSLATED,
    SOURCE
}

val SelectionBehavior.selectionCaptureEnabled: Boolean
    get() = this != SelectionBehavior.OFF

/**
 * What happens when the user clicks the window's close (X) button.
 */
@Serializable
enum class CloseButtonBehavior {
    /** Show a dialog asking whether to minimize or exit. */
    ASK,
    /** Always minimize to the system tray without asking. */
    MINIMIZE_TO_TRAY,
    /** Always exit the application without asking. */
    EXIT
}

@Serializable
enum class ServiceSelectorStyle { CLASSIC, ENHANCED }

@Serializable
enum class ServiceSelectorAppearance { ICONS_ONLY, ICONS_AND_TEXT, TEXT_ONLY }

// -------------------------------------------------------------------------
// Root configuration
// -------------------------------------------------------------------------

@Serializable
data class Configuration(
    // ---- Schema version — increment when a breaking change is made ----
    /**
     * Incremented whenever a breaking change requires a config migration.
     * Old configs that predate this field deserialise as version 1 (the default).
     * The [ConfigMigrator] upgrades older versions to the current schema.
     */
    val configVersion: Int = 1,

    // ---- Presets & Services ----
    val servicePresets: List<ServicePreset> = emptyList(),
    val activeServicePresetId: String? = null,
    val disabledServices: Set<String> = emptySet(),

    // ---- Hotkeys ----
    val hotkeys: List<HotkeyBinding> = HotkeyBinding.DEFAULTS,

    // ---- General Behaviour ----
    val launchOnSystemStartup: Boolean = false,
    val autoCheckForUpdates: Boolean = true,
    val isGlobalHotkeysEnabled: Boolean = true,
    val selectionBehavior: SelectionBehavior = SelectionBehavior.OFF,
    val selectionReadSource: SelectionReadSource = SelectionReadSource.TRANSLATION,
    /**
     * Temporary read-only compatibility input for v6 and older files. It is consumed by the
     * v6 → v7 migration and cleared before a configuration can be written again.
     */
    @SerialName("isSelectionIconEnabled")
    val legacySelectionIconEnabled: Boolean? = null,
    /**
     * The interface language, or blank for "not chosen yet, follow the operating system".
     *
     * Blank rather than "en" because the two have to be told apart and previously could not be.
     * Configuration is written without its default values, so a user who picked English stored
     * nothing, which was indistinguishable from never having picked at all — and startup, seeing
     * "en", ran operating-system detection and overrode them again on every launch. Choosing
     * English on a non-English machine therefore never stuck.
     *
     * With a blank default, picking English stores "en", which differs from the default and so
     * survives. Detection now runs only when nothing has been chosen, which is what it was for.
     */
    val interfaceLanguage: String = "",
    val isInstantTranslationEnabled: Boolean = false,
    val isSpellCheckingEnabled: Boolean = true,
    val extraOutputType: ExtraOutputType = ExtraOutputType.None,
    val extraOutputSource: ExtraOutputSource = ExtraOutputSource.Output,
    /**
     * Selected ids for the standard summary and rewrite options.
     *
     * Strings rather than enums because the vocabulary now belongs to the service: a plugin can
     * offer "Academic" or "Bullet points" without the host knowing about it. The standard ids
     * match the names of the enums these replaced, so values already on disk keep working.
     */
    val summaryLength: String = StandardOptions.SUMMARY_LENGTH.defaultValue,
    val rewriteStyle: String = StandardOptions.REWRITE_STYLE.defaultValue,

    // ---- Translation ----
    /**
     * When true, line breaks in the input text are replaced with a single space
     * before translating. Useful when copying from PDFs where each line ends with \n.
     * Mohamed's request.
     */
    val isRemoveLineBreaksEnabled: Boolean = false,
    val translationRules: List<TranslationRule> = emptyList(),

    // ---- Language Preferences ----
    /**
     * The language tag ("en", "fr", "ar", …) last selected as the target language.
     * Restored on startup so the app remembers the user's preferred language across sessions.
     * Defaults to "en" (English) so new users see a sensible translation immediately.
     */
    val preferredTargetLanguage: String = "en",

    /**
     * The language tag last selected as the source language, or "auto" for auto-detect.
     * Restored on startup.
     */
    val preferredSourceLanguage: String = "auto",

    // ---- Language Filtering ----
    /**
     * When non-empty, only these language codes appear in the target language picker.
     * Empty list means show all available languages.
     * Yan's request: "cannot disable all languages and keep only 3-4".
     */
    val pinnedLanguages: List<String> = emptyList(),

    // ---- Close button behavior ----
    val closeButtonBehavior: CloseButtonBehavior = CloseButtonBehavior.ASK,

    // ---- History ----
    val isHistoryEnabled: Boolean = true,
    val clearHistoryOnExit: Boolean = false,

    // ---- UI — Main Window ----
    val showDictionaryPanel: Boolean = false,
    val dictionaryAutoSource: DictionaryAutoSource = DictionaryAutoSource.TRANSLATED,
    val isDictionaryAutoPopupEnabled: Boolean = true,

    /**
     * Whether clicking away from a floating popup closes it.
     *
     * On by default: clicking elsewhere is how people dismiss a transient window, and a popup
     * that ignores it has to be closed deliberately every time. Off suits anyone who translates
     * a word and then works in the document beside it — for them, a click in the document
     * throwing the translation away is the annoyance instead. Pinning still overrides it either
     * way, which is what pinning is for.
     */
    val closePopupsOnClickOutside: Boolean = true,
    val mainWindowSize: Size? = null,
    val mainWindowPosition: Position? = null,
    val uiFontConfig: FontConfig = FontConfig(name = "Rubik", size = 13),
    val uiScale: Int = 100,
    val themeId: String = "os_default",
    /**
     * Which icon set to draw with, by folder name under `icons/`.
     *
     * A name rather than an index, so adding or reordering sets cannot silently change
     * somebody's choice, and an unknown one falls back rather than leaving no icons at all.
     */
    val iconSetId: String = "lucide",
    val editorFontConfig: FontConfig = FontConfig(name = "Rubik", size = 15),
    /**
     * The face used for characters the editor font has no glyph for.
     *
     * Defaults to the bundled Arabic face rather than to Rubik, which covers no Arabic at all.
     * Pointing the fallback at a font with the same gap as the primary meant right-to-left output
     * was left to whatever the platform substituted, so the same translation rendered differently
     * on Windows, on Linux and in a container with no Arabic font installed.
     *
     * Only new installations pick this up; an existing configuration keeps whatever is stored.
     */
    val editorFallbackFontConfig: FontConfig = FontConfig(name = "Noto Naskh Arabic", size = 15),
    val useUnifiedTitleBar: Boolean = true,
    val layoutPresetId: String = "classic",
    val toolbarVisibility: ToolbarVisibility = ToolbarVisibility.DEFAULT,
    /**
     * Enhanced by default: it shows which service is active and lets one be swapped in place,
     * where the classic selector only lists them. Classic remains for anyone who prefers the
     * denser row.
     */
    val serviceSelectorStyle: ServiceSelectorStyle = ServiceSelectorStyle.ENHANCED,
    val serviceSelectorAppearance: ServiceSelectorAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT,

    // ---- UI — Quick Panel (Popup) ----
    val isPopupAutoSizeEnabled: Boolean = true,
    val isPopupAutoPositionEnabled: Boolean = true,
    val popupTransparencyPercentage: Int = 5,
    /**
     * How long the translate popup waits before hiding itself.
     *
     * Three seconds was not enough to read a translated sentence, let alone a paragraph -- the
     * popup was gone before most people finished. The countdown restarts on any activity, so a
     * longer default costs nothing to someone who has already moved on.
     */
    val popupIdleTimeoutSeconds: Int = 12,
    val popupLastKnownSize: Size = Size(width = 450, height = 250),
    val popupLastKnownPosition: Position = Position(x = 0, y = 0),

    // ---- UI — Quick Dictionary Popup ----
    val quickDictionaryLastKnownSize: Size = Size(width = 420, height = 400),
    val quickDictionaryLastKnownPosition: Position = Position(x = 0, y = 0),
    /** Wider than the dictionary popup because it holds a grid rather than a column of text. */
    val imageSearchLastKnownSize: Size = Size(width = 560, height = 460),
    val imageSearchLastKnownPosition: Position = Position(x = 0, y = 0),
    val isQuickDictionaryPinned: Boolean = false,
    val isQuickDictionaryAutoPositionEnabled: Boolean = true,
    /** Longer than the translate popup: definitions are read and compared, not glanced at. */
    val quickDictionaryIdleTimeoutSeconds: Int = 20,
    val quickDictionaryTransparencyPercentage: Int = 5,
    val isImageSearchAutoPositionEnabled: Boolean = true,
    val imageSearchTransparencyPercentage: Int = 5,

    // ---- Donation nudge ----
    /**
     * Set to `true` the first time the one-time donation nudge is shown.
     * Prevents the nudge from ever appearing again after it has been displayed once.
     */
    val donationNudgeShown: Boolean = false,

    // ---- Network ----
    val network: NetworkConfig = NetworkConfig()
) {
    fun getActivePreset(): ServicePreset? =
        servicePresets.find { it.id == activeServicePresetId }

    companion object {
        val DEFAULT: Configuration by lazy {
            val defaultPreset = ServicePreset.createDefault()
            Configuration(
                configVersion                = ConfigMigrator.CURRENT_VERSION,
                servicePresets               = listOf(defaultPreset),
                activeServicePresetId        = defaultPreset.id,
                disabledServices             = emptySet(),
                hotkeys                      = HotkeyBinding.DEFAULTS,
                launchOnSystemStartup        = false,
                isGlobalHotkeysEnabled       = true,
                selectionBehavior            = SelectionBehavior.OFF,
                selectionReadSource          = SelectionReadSource.TRANSLATION,
                legacySelectionIconEnabled   = null,
                autoCheckForUpdates          = true,
                interfaceLanguage            = "en",
                isInstantTranslationEnabled  = false,
                isSpellCheckingEnabled       = true,
                extraOutputType              = ExtraOutputType.None,
                extraOutputSource            = ExtraOutputSource.Output,
                summaryLength                = StandardOptions.SUMMARY_LENGTH.defaultValue,
                rewriteStyle                 = StandardOptions.REWRITE_STYLE.defaultValue,
                isRemoveLineBreaksEnabled    = false,
                pinnedLanguages              = emptyList(),
                closeButtonBehavior          = CloseButtonBehavior.ASK,
                isHistoryEnabled             = true,
                clearHistoryOnExit           = false,
                uiScale                      = 100,
                themeId                      = "os_default",
                uiFontConfig                 = FontConfig(name = "Rubik", size = 13),
                editorFontConfig             = FontConfig(name = "Rubik", size = 15),
                editorFallbackFontConfig     = FontConfig(name = "Noto Naskh Arabic", size = 15),
                useUnifiedTitleBar           = true,
                layoutPresetId               = "classic",
                toolbarVisibility            = ToolbarVisibility.DEFAULT,
                isPopupAutoSizeEnabled       = true,
                isPopupAutoPositionEnabled   = true,
                popupTransparencyPercentage  = 5,
                popupLastKnownSize           = Size(width = 450, height = 250),
                popupLastKnownPosition       = Position(x = 0, y = 0)
            )
        }
    }
}

package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import kotlinx.serialization.Serializable
import javax.swing.KeyStroke
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class ExtraOutputType {
    None, BackwardTranslate, Summarize, Rewrite
}

@Serializable
enum class ExtraOutputSource {
    Input, Output
}

@Serializable
enum class TextSource {
    Input, Output, ExtraOutput
}

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
// UI layout types
// -------------------------------------------------------------------------
@Serializable
data class ToolbarVisibility(
    val isHistoryBarVisible: Boolean    = true,
    val isLanguageBarVisible: Boolean   = true,
    val isServicesPanelVisible: Boolean = true,
    val isStatusBarVisible: Boolean     = true
) {
    companion object { val DEFAULT = ToolbarVisibility() }
}

@Serializable
data class FontConfig(val name: String, val size: Int) {
    init { require(size > 0) { "Font size must be positive, was $size." } }
}

@Serializable
data class Size(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) { "Size must be positive, was ${width}x${height}." } }
}

@Serializable
data class Position(val x: Int, val y: Int) {
    init { require(x >= 0 && y >= 0) { "Position must be non-negative, was ($x, $y)." } }
}

// -------------------------------------------------------------------------
// Hotkeys
// -------------------------------------------------------------------------

/**
 * Stable identifiers for every bindable action.
 * Never rename these — they are persisted in the config file.
 */
@Serializable
enum class HotkeyAction {
    SHOW_MAIN_WINDOW,
    SHOW_QUICK_TRANSLATE,
    LISTEN_TO_TEXT,
    OPEN_OCR,
    REPLACE_WITH_TRANSLATION,  // Rob #2 / Davide — translate and replace selected text
    CYCLE_TARGET_LANGUAGE,     // Yan #3 — cycle through available target languages
    SHOW_DICTIONARY,           // open floating dictionary popup
    SHOW_IMAGES,               // open floating image popup (default: Ctrl+Shift+Q, GLOBAL)
    TRANSLATE,                 // trigger translation (default: Ctrl+Enter, LOCAL)
    FOCUS_INPUT,               // move keyboard focus to the input text pane (default: Alt+1, LOCAL)
    FOCUS_OUTPUT,              // move keyboard focus to the output text pane (default: Alt+2, LOCAL)
    FOCUS_EXTRA_OUTPUT,        // move keyboard focus to the extra-output pane (default: Alt+3, LOCAL)
    COPY_TRANSLATION,          // copy the translated text (default: Ctrl+Shift+C, LOCAL)
    CLEAR_INPUT,               // clear the input pane (default: Ctrl+Shift+X, LOCAL)
    SWAP_LANGUAGES,            // swap source and target languages (default: Ctrl+Shift+S, LOCAL)
    OPEN_SETTINGS,             // open the settings dialog (default: Ctrl+Comma, LOCAL)
    SHOW_HISTORY,              // open the translation history dialog (default: Ctrl+Shift+H, LOCAL)
    TRANSLATE_DOCUMENT         // open the document translation dialog (default: Ctrl+Shift+D, LOCAL)
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

/**
 * Whether a hotkey fires globally (system-wide via jKeymaster) or
 * locally (only when QTranslate has focus, via Swing InputMap).
 *
 * Global hotkeys intercept keys from any application — use sparingly.
 * Local hotkeys only fire inside QTranslate — safe for common shortcuts.
 *
 * Dinar's request: allow per-action control so e.g. Ctrl+Tab isn't
 * stolen from the browser while still keeping Ctrl+Q global.
 */
@Serializable
enum class HotkeyScope {
    GLOBAL,  // Registered with jKeymaster — fires system-wide
    LOCAL    // Registered via Swing InputMap — fires only inside QTranslate
}

/**
 * A user-configurable hotkey binding stored as raw [keyCode] + [modifiers] integers.
 *
 * ### Why integers, not a string?
 * [KeyStroke.getKeyStroke] (String) fails for many keys (slash, page up, numpad keys).
 * Storing keyCode + modifiers avoids all string parsing.
 * Reconstruct: `KeyStroke.getKeyStroke(keyCode, modifiers)`
 *
 * [keyCode] = 0 means "no binding" (SHOW_MAIN_WINDOW uses double-Ctrl via JNativeHook).
 *
 * [isDoubleCtrlEnabled] only applies to [HotkeyAction.SHOW_MAIN_WINDOW].
 * When false the double-tap Ctrl sequence is suppressed so other applications
 * that react to Ctrl-key events are not accidentally triggered.
 */
@Serializable
data class HotkeyBinding(
    val action: HotkeyAction,
    val keyCode: Int = 0,
    val modifiers: Int = 0,
    val isEnabled: Boolean = true,
    val scope: HotkeyScope = HotkeyScope.GLOBAL,
    val isDoubleCtrlEnabled: Boolean = true   // SHOW_MAIN_WINDOW only
) {
    val hasBinding: Boolean get() = keyCode != 0

    fun toKeyStroke(): KeyStroke? =
        if (hasBinding) KeyStroke.getKeyStroke(keyCode, modifiers) else null

    companion object {
        val DEFAULTS: List<HotkeyBinding> = listOf(
            // SHOW_MAIN_WINDOW: double-Ctrl via JNativeHook — no KeyStroke, always GLOBAL
            HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW,         keyCode = 0,                                          modifiers = 0,                                         scope = HotkeyScope.GLOBAL, isDoubleCtrlEnabled = true),
            HotkeyBinding(HotkeyAction.SHOW_QUICK_TRANSLATE,     keyCode = java.awt.event.KeyEvent.VK_Q,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.LISTEN_TO_TEXT,           keyCode = java.awt.event.KeyEvent.VK_E,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.OPEN_OCR,                 keyCode = java.awt.event.KeyEvent.VK_I,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.REPLACE_WITH_TRANSLATION, keyCode = java.awt.event.KeyEvent.VK_T,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.CYCLE_TARGET_LANGUAGE,    keyCode = java.awt.event.KeyEvent.VK_L,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.SHOW_DICTIONARY,          keyCode = java.awt.event.KeyEvent.VK_D,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            // Shift+the quick-translate key: this is the same gesture on the same selection,
            // asking for pictures instead of words. Ctrl+Shift+I would read as a variant of OCR.
            HotkeyBinding(HotkeyAction.SHOW_IMAGES,              keyCode = java.awt.event.KeyEvent.VK_Q,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.TRANSLATE,                keyCode = java.awt.event.KeyEvent.VK_ENTER,            modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.FOCUS_INPUT,              keyCode = java.awt.event.KeyEvent.VK_1,                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,   scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.FOCUS_OUTPUT,             keyCode = java.awt.event.KeyEvent.VK_2,                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,   scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.FOCUS_EXTRA_OUTPUT,       keyCode = java.awt.event.KeyEvent.VK_3,                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,   scope = HotkeyScope.LOCAL),
            // All LOCAL — these act on the focused window, so they must not take the key
            // combination away from other applications the way a GLOBAL binding would.
            HotkeyBinding(HotkeyAction.COPY_TRANSLATION,         keyCode = java.awt.event.KeyEvent.VK_C,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.CLEAR_INPUT,              keyCode = java.awt.event.KeyEvent.VK_X,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.SWAP_LANGUAGES,           keyCode = java.awt.event.KeyEvent.VK_S,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OPEN_SETTINGS,            keyCode = java.awt.event.KeyEvent.VK_COMMA,            modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.SHOW_HISTORY,             keyCode = java.awt.event.KeyEvent.VK_H,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.TRANSLATE_DOCUMENT,       keyCode = java.awt.event.KeyEvent.VK_D,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
        )
    }
}

// -------------------------------------------------------------------------
// Service presets
// -------------------------------------------------------------------------

@Serializable
data class ServicePreset(
    val id: String,
    val name: String,
    val selectedServices: Map<ServiceType, String?>
) {
    companion object {

        const val DEFAULT_PRESET_NAME = "__default__" // internal sentinel, never shown to user

        // Composed ids, matching what the registry keys these services under. A fresh install must
        // be valid on its own rather than depend on a migration to become so.
        private const val GOOGLE = "google-services"
        private val DEFAULT_TRANSLATOR    = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-translator")
        private val DEFAULT_TTS           = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-tts")
        private val DEFAULT_SPELL_CHECKER = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-spell-checker")
        private val DEFAULT_OCR           = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-ocr")
        private val DEFAULT_DICTIONARY    = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-dictionary")

        @OptIn(ExperimentalUuidApi::class)
        fun createDefault(name: String = DEFAULT_PRESET_NAME): ServicePreset = ServicePreset(
            id = Uuid.random().toString(),
            name = name,
            selectedServices = mapOf(
                ServiceType.TRANSLATOR    to DEFAULT_TRANSLATOR,
                ServiceType.TTS           to DEFAULT_TTS,
                ServiceType.SPELL_CHECKER to DEFAULT_SPELL_CHECKER,
                ServiceType.OCR           to DEFAULT_OCR,
                ServiceType.DICTIONARY    to DEFAULT_DICTIONARY
            )
        )
    }
}


@Serializable
data class TranslationRule(
    val sourceLanguage: String,
    val targetLanguage: String
)

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
    val isSelectionIconEnabled: Boolean = false,
    val interfaceLanguage: String = "en",
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
    val editorFontConfig: FontConfig = FontConfig(name = "Rubik", size = 15),
    val editorFallbackFontConfig: FontConfig = FontConfig(name = "Rubik", size = 15),
    val useUnifiedTitleBar: Boolean = true,
    val layoutPresetId: String = "classic",
    val toolbarVisibility: ToolbarVisibility = ToolbarVisibility.DEFAULT,
    val serviceSelectorStyle: ServiceSelectorStyle = ServiceSelectorStyle.CLASSIC,
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
    val donationNudgeShown: Boolean = false
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
                isSelectionIconEnabled       = false,
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
                editorFallbackFontConfig     = FontConfig(name = "Rubik", size = 15),
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

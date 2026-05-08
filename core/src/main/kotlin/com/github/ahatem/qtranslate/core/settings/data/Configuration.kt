package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.rewriter.RewriteStyle
import com.github.ahatem.qtranslate.api.summarizer.SummaryLength
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
    TRANSLATE                  // trigger translation (default: Ctrl+Enter, LOCAL)
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
            HotkeyBinding(HotkeyAction.TRANSLATE,                keyCode = java.awt.event.KeyEvent.VK_ENTER,            modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.LOCAL),
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

        private const val DEFAULT_TRANSLATOR    = "google-translator"
        private const val DEFAULT_TTS           = "google-tts"
        private const val DEFAULT_SPELL_CHECKER = "google-spell-checker"
        private const val DEFAULT_OCR           = "google-ocr"
        private const val DEFAULT_DICTIONARY    = "google-dictionary"

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
    val interfaceLanguage: String = "en",
    val isInstantTranslationEnabled: Boolean = false,
    val isSpellCheckingEnabled: Boolean = true,
    val extraOutputType: ExtraOutputType = ExtraOutputType.None,
    val extraOutputSource: ExtraOutputSource = ExtraOutputSource.Output,
    val summaryLength: SummaryLength = SummaryLength.MEDIUM,
    val rewriteStyle: RewriteStyle = RewriteStyle.FORMAL,

    // ---- Translation ----
    /**
     * When true, line breaks in the input text are replaced with a single space
     * before translating. Useful when copying from PDFs where each line ends with \n.
     * Mohamed's request.
     */
    val isRemoveLineBreaksEnabled: Boolean = false,
    val translationRules: List<TranslationRule> = emptyList(),

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
    val mainWindowSize: Size? = null,
    val mainWindowPosition: Position? = null,
    val uiFontConfig: FontConfig = FontConfig(name = "Rubik", size = 13),
    val uiScale: Int = 100,
    val themeId: String = "builtin:hiberbee_dark",
    val editorFontConfig: FontConfig = FontConfig(name = "Rubik", size = 15),
    val editorFallbackFontConfig: FontConfig = FontConfig(name = "Rubik", size = 15),
    val useUnifiedTitleBar: Boolean = true,
    val layoutPresetId: String = "classic",
    val toolbarVisibility: ToolbarVisibility = ToolbarVisibility.DEFAULT,

    // ---- UI — Quick Panel (Popup) ----
    val isPopupAutoSizeEnabled: Boolean = true,
    val isPopupAutoPositionEnabled: Boolean = true,
    val popupTransparencyPercentage: Int = 5,
    val popupIdleTimeoutSeconds: Int = 3,
    val popupLastKnownSize: Size = Size(width = 450, height = 250),
    val popupLastKnownPosition: Position = Position(x = 0, y = 0),

    // ---- UI — Quick Dictionary Popup ----
    val quickDictionaryLastKnownSize: Size = Size(width = 420, height = 400),
    val quickDictionaryLastKnownPosition: Position = Position(x = 0, y = 0),
    val isQuickDictionaryPinned: Boolean = false,
    val isQuickDictionaryAutoPositionEnabled: Boolean = true,
    val quickDictionaryIdleTimeoutSeconds: Int = 8,
    val quickDictionaryTransparencyPercentage: Int = 5,

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
                servicePresets               = listOf(defaultPreset),
                activeServicePresetId        = defaultPreset.id,
                disabledServices             = emptySet(),
                hotkeys                      = HotkeyBinding.DEFAULTS,
                launchOnSystemStartup        = false,
                isGlobalHotkeysEnabled       = true,
                autoCheckForUpdates          = true,
                interfaceLanguage            = "en",
                isInstantTranslationEnabled  = false,
                isSpellCheckingEnabled       = true,
                extraOutputType              = ExtraOutputType.None,
                extraOutputSource            = ExtraOutputSource.Output,
                summaryLength                = SummaryLength.MEDIUM,
                rewriteStyle                 = RewriteStyle.FORMAL,
                isRemoveLineBreaksEnabled    = false,
                pinnedLanguages              = emptyList(),
                closeButtonBehavior          = CloseButtonBehavior.ASK,
                isHistoryEnabled             = true,
                clearHistoryOnExit           = false,
                uiScale                      = 100,
                themeId                      = "builtin:hiberbee_dark",
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
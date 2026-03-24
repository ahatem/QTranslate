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
    CYCLE_TARGET_LANGUAGE      // Yan #3 — cycle through available target languages
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
 */
@Serializable
data class HotkeyBinding(
    val action: HotkeyAction,
    val keyCode: Int = 0,
    val modifiers: Int = 0,
    val isEnabled: Boolean = true,
    val scope: HotkeyScope = HotkeyScope.GLOBAL
) {
    val hasBinding: Boolean get() = keyCode != 0

    fun toKeyStroke(): KeyStroke? =
        if (hasBinding) KeyStroke.getKeyStroke(keyCode, modifiers) else null

    companion object {
        val DEFAULTS: List<HotkeyBinding> = listOf(
            // SHOW_MAIN_WINDOW: double-Ctrl via JNativeHook — no KeyStroke, always GLOBAL
            HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW,         keyCode = 0,                                          modifiers = 0,                                         scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.SHOW_QUICK_TRANSLATE,     keyCode = java.awt.event.KeyEvent.VK_Q,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.LISTEN_TO_TEXT,           keyCode = java.awt.event.KeyEvent.VK_E,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.OPEN_OCR,                 keyCode = java.awt.event.KeyEvent.VK_I,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.REPLACE_WITH_TRANSLATION, keyCode = java.awt.event.KeyEvent.VK_T,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.CYCLE_TARGET_LANGUAGE,    keyCode = java.awt.event.KeyEvent.VK_L,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.LOCAL),
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
        private const val DEFAULT_TRANSLATOR    = "google-translator"
        private const val DEFAULT_TTS           = "google-tts"
        private const val DEFAULT_SPELL_CHECKER = "google-spell-checker"
        private const val DEFAULT_OCR           = "google-ocr"
        private const val DEFAULT_DICTIONARY    = "google-dictionary"

        @OptIn(ExperimentalUuidApi::class)
        fun createDefault(name: String = "Default"): ServicePreset = ServicePreset(
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

// -------------------------------------------------------------------------
// Root configuration
// -------------------------------------------------------------------------

@Serializable
data class Configuration(
    // ---- Presets & Services ----
    val servicePresets: List<ServicePreset>,
    val activeServicePresetId: String?,
    val disabledServices: Set<String>,

    // ---- Hotkeys ----
    val hotkeys: List<HotkeyBinding> = HotkeyBinding.DEFAULTS,

    // ---- General Behaviour ----
    val launchOnSystemStartup: Boolean,
    val autoCheckForUpdates: Boolean,
    val isGlobalHotkeysEnabled: Boolean,
    val interfaceLanguage: String,
    val isInstantTranslationEnabled: Boolean,
    val isSpellCheckingEnabled: Boolean,
    val extraOutputType: ExtraOutputType,
    val extraOutputSource: ExtraOutputSource,
    val summaryLength: SummaryLength = SummaryLength.MEDIUM,
    val rewriteStyle: RewriteStyle = RewriteStyle.FORMAL,

    // ---- Translation ----
    /**
     * When true, line breaks in the input text are replaced with a single space
     * before translating. Useful when copying from PDFs where each line ends with \n.
     * Mohamed's request.
     */
    val isRemoveLineBreaksEnabled: Boolean = false,

    // ---- Language Filtering ----
    /**
     * When non-empty, only these language codes appear in the target language picker.
     * Empty list means show all available languages.
     * Yan's request: "cannot disable all languages and keep only 3-4".
     */
    val pinnedLanguages: List<String> = emptyList(),

    // ---- History ----
    val isHistoryEnabled: Boolean,
    val clearHistoryOnExit: Boolean,

    // ---- UI — Main Window ----
    val uiFontConfig: FontConfig,
    val uiScale: Int,
    val themeId: String,
    val editorFontConfig: FontConfig,
    val editorFallbackFontConfig: FontConfig,
    val useUnifiedTitleBar: Boolean,
    val layoutPresetId: String,
    val toolbarVisibility: ToolbarVisibility,

    // ---- UI — Quick Panel (Popup) ----
    val isPopupAutoSizeEnabled: Boolean,
    val isPopupAutoPositionEnabled: Boolean,
    val popupTransparencyPercentage: Int,
    val popupLastKnownSize: Size,
    val popupLastKnownPosition: Position
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
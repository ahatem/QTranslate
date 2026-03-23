package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import kotlinx.serialization.Serializable
import javax.swing.KeyStroke
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class ExtraOutputType {
    None,
    BackwardTranslate,
    Summarize,
    Rewrite
}

@Serializable
enum class ExtraOutputSource {
    Input,
    Output
}

@Serializable
enum class TextSource {
    Input,
    Output,
    ExtraOutput
}

// -------------------------------------------------------------------------
// UI layout types
// -------------------------------------------------------------------------
@Serializable
data class ToolbarVisibility(
    val isHistoryBarVisible: Boolean = true,
    val isLanguageBarVisible: Boolean = true,
    val isServicesPanelVisible: Boolean = true,
    val isStatusBarVisible: Boolean = true
) {
    companion object {
        val DEFAULT = ToolbarVisibility()
    }
}

@Serializable
data class FontConfig(
    val name: String,
    val size: Int
) {
    init {
        require(size > 0) { "Font size must be positive, was $size." }
    }
}

@Serializable
data class Size(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0 && height > 0) { "Size dimensions must be positive, was ${width}x${height}." }
    }
}

@Serializable
data class Position(
    val x: Int,
    val y: Int
) {
    init {
        require(x >= 0 && y >= 0) { "Position coordinates must be non-negative, was ($x, $y)." }
    }
}


// -------------------------------------------------------------------------
// Hotkeys
// -------------------------------------------------------------------------

/**
 * Stable identifiers for every bindable action in the application.
 * Never rename these — they are persisted in the config file.
 */
@Serializable
enum class HotkeyAction {
    SHOW_MAIN_WINDOW,
    SHOW_QUICK_TRANSLATE,
    LISTEN_TO_TEXT,
    OPEN_OCR
}

/**
 * A user-configurable hotkey binding stored as raw [keyCode] + [modifiers] integers.
 * - Reconstruct: `KeyStroke.getKeyStroke(keyCode, modifiers)`
 * - Display:     `KeyEvent.getKeyText(keyCode)` + modifier names
 *
 * [keyCode] = 0 and [modifiers] = 0 means "no binding" (used for SHOW_MAIN_WINDOW
 * which is handled by the double-Ctrl JNativeHook sequence, not a KeyStroke).
 */
@Serializable
data class HotkeyBinding(
    val action: HotkeyAction,
    val keyCode: Int = 0,
    val modifiers: Int = 0,
    val isEnabled: Boolean = true
) {
    val hasBinding: Boolean get() = keyCode != 0

    fun toKeyStroke(): KeyStroke? =
        if (hasBinding) KeyStroke.getKeyStroke(keyCode, modifiers) else null

    companion object {
        val DEFAULTS: List<HotkeyBinding> = listOf(
            // SHOW_MAIN_WINDOW uses double-Ctrl via JNativeHook — no KeyStroke
            HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW,     keyCode = 0,                                    modifiers = 0),
            HotkeyBinding(HotkeyAction.SHOW_QUICK_TRANSLATE, keyCode = java.awt.event.KeyEvent.VK_Q,         modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK),
            HotkeyBinding(HotkeyAction.LISTEN_TO_TEXT,       keyCode = java.awt.event.KeyEvent.VK_E,         modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK),
            HotkeyBinding(HotkeyAction.OPEN_OCR,             keyCode = java.awt.event.KeyEvent.VK_I,         modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK),
        )
    }
}

// -------------------------------------------------------------------------
// Service presets
// -------------------------------------------------------------------------

/**
 * A named set of service selections — one service ID per [ServiceType].
 *
 * A `null` value for a given [ServiceType] means "use the first available service
 * of that type" (the [ActiveServiceManager] fallback). This is preferred over
 * hardcoding a specific service ID when a default selection doesn't matter.
 *
 * The app ships with Google as the default provider for all service types.
 */
@Serializable
data class ServicePreset(
    val id: String,
    val name: String,
    val selectedServices: Map<ServiceType, String?>
) {
    companion object {
        /**
         * The stable IDs of the built-in default services.
         * These IDs match the `id` properties declared in the shipped Google plugin services.
         * Do not change these without also updating the corresponding service implementations.
         */
        private const val DEFAULT_TRANSLATOR   = "google-translator"
        private const val DEFAULT_TTS          = "google-tts"
        private const val DEFAULT_SPELL_CHECKER = "google-spell-checker"
        private const val DEFAULT_OCR          = "google-ocr"
        private const val DEFAULT_DICTIONARY   = "google-dictionary"

        /**
         * Creates a new preset with Google services pre-selected.
         * The app ships with Google and Bing as built-in plugins — Google is the default.
         */
        @OptIn(ExperimentalUuidApi::class)
        fun createDefault(name: String = "Default"): ServicePreset = ServicePreset(
            id = Uuid.random().toString(),
            name = name,
            selectedServices = mapOf(
                ServiceType.TRANSLATOR   to DEFAULT_TRANSLATOR,
                ServiceType.TTS          to DEFAULT_TTS,
                ServiceType.SPELL_CHECKER to DEFAULT_SPELL_CHECKER,
                ServiceType.OCR          to DEFAULT_OCR,
                ServiceType.DICTIONARY   to DEFAULT_DICTIONARY
            )
        )
    }
}

// -------------------------------------------------------------------------
// Root configuration
// -------------------------------------------------------------------------

/**
 * The complete application configuration.
 *
 * This is a pure data class — no business logic lives here.
 * All transformations are performed via the extension functions in
 * [ConfigurationExtensions]. The UI layer may add its own extensions
 * (e.g. scaled font helpers) without polluting this domain model.
 *
 * Persisted as JSON by [SettingsRepository].
 */
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
    /** Returns the currently active [ServicePreset], or `null` if none is set. */
    fun getActivePreset(): ServicePreset? =
        servicePresets.find { it.id == activeServicePresetId }

    companion object {
        val DEFAULT: Configuration by lazy {
            val defaultPreset = ServicePreset.createDefault()
            Configuration(
                servicePresets          = listOf(defaultPreset),
                activeServicePresetId   = defaultPreset.id,
                disabledServices        = emptySet(),

                hotkeys                 = HotkeyBinding.DEFAULTS,

                launchOnSystemStartup   = false,
                isGlobalHotkeysEnabled  = true,
                autoCheckForUpdates     = true,
                interfaceLanguage       = "en",
                isInstantTranslationEnabled = false,
                isSpellCheckingEnabled  = true,
                extraOutputType         = ExtraOutputType.None,
                extraOutputSource       = ExtraOutputSource.Output,

                isHistoryEnabled        = true,
                clearHistoryOnExit      = false,

                uiScale                 = 100,
                themeId                 = "custom:xcode_dark",
                uiFontConfig            = FontConfig(name = "Rubik", size = 13),
                editorFontConfig        = FontConfig(name = "Rubik", size = 15),
                editorFallbackFontConfig = FontConfig(name = "Rubik", size = 15),
                useUnifiedTitleBar      = true,
                layoutPresetId          = "classic",
                toolbarVisibility       = ToolbarVisibility.DEFAULT,

                isPopupAutoSizeEnabled     = true,
                isPopupAutoPositionEnabled = true,
                popupTransparencyPercentage = 5,
                popupLastKnownSize     = Size(width = 450, height = 250),
                popupLastKnownPosition = Position(x = 0, y = 0)
            )
        }
    }
}
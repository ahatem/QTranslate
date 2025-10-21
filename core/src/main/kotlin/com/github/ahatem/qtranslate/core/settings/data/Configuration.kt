package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Defines the type of extra output the user wants the application to generate.
 * The application core will look for a service that provides the requested capability.
 */
@Serializable
enum class ExtraOutputType {
    /** No extra output is generated. */
    None,

    /**
     * Request a backward translation (translating the result back to the source language).
     * This is handled by the core application logic using the selected `Translator`.
     */
    BackwardTranslate,

    /**
     * Request a summary of the text.
     * The core will find and use a service with the `SummarizationProvider` capability.
     */
    Summarize,

    /**
     * Request a rewritten version of the text (e.g., for style or tone).
     * The core will find and use a service with the `RewritingProvider` capability.
     */
    Rewrite
}

/** Defines whether the extra output should be based on the input or the translated text. */
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


/** Visibility flags for the various toolbars in the main window. */
@Serializable
data class ToolbarVisibility(
    val isHistoryBarVisible: Boolean = true,
    val isLanguageBarVisible: Boolean = true,
    val isServicesPanelVisible: Boolean = true, // This is the service selector toolbar
    val isStatusBarVisible: Boolean = true
) {
    companion object {
        val DEFAULT = ToolbarVisibility()
    }
}

/** Font configuration for text input and output. */
@Serializable
data class FontConfig(
    val name: String,
    val size: Int
) {
    init {
        require(size > 0) { "Font size must be positive" }
    }
}

/** Dimensions of a UI element. */
@Serializable
data class Size(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0 && height > 0) { "Size dimensions must be positive" }
    }
}

/** Screen position of a UI element. */
@Serializable
data class Position(
    val x: Int,
    val y: Int
) {
    init {
        require(x >= 0 && y >= 0) { "Position coordinates must be non-negative" }
    }
}


@Serializable
data class ServicePreset(
    val id: String,
    val name: String,
    val selectedServices: Map<ServiceType, String?>
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun createDefault(name: String = "Default"): ServicePreset {
            return ServicePreset(
                id = Uuid.random().toString(),
                name = name,
                selectedServices = mapOf(
                    ServiceType.TRANSLATOR to "google-translator",
                    ServiceType.TTS to "google-tts",
                    ServiceType.SPELL_CHECKER to "google-spellchecker",
                    ServiceType.OCR to "google-ocr",
                    ServiceType.DICTIONARY to "google-dictionary"
                )
            )
        }
    }
}


@Serializable
data class Configuration(

    // ───────────────────────────────
    // Presets & Services
    // ───────────────────────────────
    /** The list of all user-created service presets. */
    val servicePresets: List<ServicePreset>,

    /** The ID of the currently active service preset. */
    val activeServicePresetId: String?,

    /** A set of service IDs that the user has explicitly disabled globally. */
    val disabledServices: Set<String>,


    // ───────────────────────────────
    // General Behavior
    // ───────────────────────────────
    val launchOnSystemStartup: Boolean,
    val autoCheckForUpdates: Boolean,
    val isGlobalHotkeysEnabled: Boolean,
    val interfaceLanguage: String,
    val isInstantTranslationEnabled: Boolean,
    val isSpellCheckingEnabled: Boolean,
    val extraOutputType: ExtraOutputType,
    val extraOutputSource: ExtraOutputSource,


    // ───────────────────────────────
    // History
    // ───────────────────────────────
    val isHistoryEnabled: Boolean,
    val clearHistoryOnExit: Boolean,


    // ───────────────────────────────
    // UI - Main Window
    // ───────────────────────────────
    val uiFontConfig: FontConfig,
    val uiScale: Int,
    val themeId: String,
    val editorFontConfig: FontConfig,
    val editorFallbackFontConfig: FontConfig,
    val useUnifiedTitleBar: Boolean,
    val layoutPresetId: String,
    val toolbarVisibility: ToolbarVisibility,


    // ───────────────────────────────
    // UI - Quick Panel (Popup Window)
    // ───────────────────────────────
    val isPopupAutoSizeEnabled: Boolean,
    val isPopupAutoPositionEnabled: Boolean,
    val popupTransparencyPercentage: Int,
    val popupLastKnownSize: Size,
    val popupLastKnownPosition: Position

) {

    fun getActivePreset(): ServicePreset? {
        return servicePresets.find { it.id == activeServicePresetId }
    }

    val uiFont: FontConfig
        get() = uiFontConfig.copy(size = (uiFontConfig.size * (uiScale / 100f)).toInt())

    val editorFont: FontConfig
        get() = editorFontConfig.copy(size = (editorFontConfig.size * (uiScale / 100f)).toInt())

    val editorFallbackFont: FontConfig
        get() = editorFallbackFontConfig.copy(size = (editorFallbackFontConfig.size * (uiScale / 100f)).toInt())

    companion object {
        val DEFAULT: Configuration by lazy {
            val defaultPreset = ServicePreset.createDefault()
            Configuration(
                // Presets & Services
                servicePresets = listOf(defaultPreset),
                activeServicePresetId = defaultPreset.id,
                disabledServices = emptySet(),

                // General Behavior
                launchOnSystemStartup = false,
                isGlobalHotkeysEnabled = true,
                autoCheckForUpdates = true,
                interfaceLanguage = "en",
                isInstantTranslationEnabled = false,
                isSpellCheckingEnabled = true,
                extraOutputType = ExtraOutputType.None,
                extraOutputSource = ExtraOutputSource.Output,

                // History
                isHistoryEnabled = true,
                clearHistoryOnExit = false,

                // UI - Main Window
                uiScale = 200,
//                themeId = "custom:kintsugi_dark",
                themeId = "custom:github_dark_dimmed",
                uiFontConfig = FontConfig(name = "IBM Plex Sans", size = 13),
                editorFontConfig = FontConfig(name = "IBM Plex Sans", size = 15),
                editorFallbackFontConfig = FontConfig(name = "IBM Plex Sans Arabic", size = 15),
                useUnifiedTitleBar = true,
                layoutPresetId = "classic",
                toolbarVisibility = ToolbarVisibility.DEFAULT,

                // UI - Quick Panel
                isPopupAutoSizeEnabled = true,
                isPopupAutoPositionEnabled = true,
                popupTransparencyPercentage = 5,
                popupLastKnownSize = Size(width = 450, height = 250),
                popupLastKnownPosition = Position(x = 0, y = 0)
            )
        }
    }
}
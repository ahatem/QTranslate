package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Defines the type of extra output the user wants the application to generate.
 */
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

/**
 * Visibility flags for the various toolbars in the main window.
 */
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

/**
 * Font configuration for text rendering.
 */
@Serializable
data class FontConfig(
    val name: String,
    val size: Int
) {
    init {
        require(size > 0) { "Font size must be positive" }
    }
}

/**
 * Dimensions of a UI element.
 */
@Serializable
data class Size(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0 && height > 0) { "Size dimensions must be positive" }
    }
}

/**
 * Screen position of a UI element.
 */
@Serializable
data class Position(
    val x: Int,
    val y: Int
) {
    init {
        require(x >= 0 && y >= 0) { "Position coordinates must be non-negative" }
    }
}

/**
 * A service preset defines which services are selected for each service type.
 */
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

/**
 * Application configuration.
 *
 * This is a pure data class with no business logic.
 * All transformations are done via extension functions.
 *
 * Note: Font scaling is NOT done here - it's handled by UI-layer
 * extensions to keep presentation logic out of the domain model.
 */
@Serializable
data class Configuration(
    // Presets & Services
    val servicePresets: List<ServicePreset>,
    val activeServicePresetId: String?,
    val disabledServices: Set<String>,

    // General Behavior
    val launchOnSystemStartup: Boolean,
    val autoCheckForUpdates: Boolean,
    val isGlobalHotkeysEnabled: Boolean,
    val interfaceLanguage: String,
    val isInstantTranslationEnabled: Boolean,
    val isSpellCheckingEnabled: Boolean,
    val extraOutputType: ExtraOutputType,
    val extraOutputSource: ExtraOutputSource,

    // History
    val isHistoryEnabled: Boolean,
    val clearHistoryOnExit: Boolean,

    // UI - Main Window
    val uiFontConfig: FontConfig,
    val uiScale: Int,
    val themeId: String,
    val editorFontConfig: FontConfig,
    val editorFallbackFontConfig: FontConfig,
    val useUnifiedTitleBar: Boolean,
    val layoutPresetId: String,
    val toolbarVisibility: ToolbarVisibility,

    // UI - Quick Panel (Popup Window)
    val isPopupAutoSizeEnabled: Boolean,
    val isPopupAutoPositionEnabled: Boolean,
    val popupTransparencyPercentage: Int,
    val popupLastKnownSize: Size,
    val popupLastKnownPosition: Position
) {
    /**
     * Returns the currently active preset, or null if none is set.
     */
    fun getActivePreset(): ServicePreset? {
        return servicePresets.find { it.id == activeServicePresetId }
    }

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
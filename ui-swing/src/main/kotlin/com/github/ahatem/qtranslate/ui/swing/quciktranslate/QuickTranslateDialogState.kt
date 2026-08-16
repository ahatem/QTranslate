package com.github.ahatem.qtranslate.ui.swing.quciktranslate

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * An immutable snapshot of all data required to render the QuickTranslateDialog.
 */
data class QuickTranslateDialogState(
    val isVisible: Boolean,
    val isLoading: Boolean,
    val translatedText: String,
    val isPinned: Boolean,
    /** Bumped when the user asks for this popup again; a change restarts the countdown. */
    val triggerCount: Int,
    /**
     * Whether speech is playing right now.
     *
     * The Listen button turns into a stop button while it is, the same as in the main window.
     * Without it, pressing Listen again started a second playback on top of the first.
     */
    val isTtsPlaying: Boolean = false,
    /** Short definition for a single-word result; empty for anything longer. */
    val definition: String,

    // --- The Data is now a first-class citizen ---
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    /**
     * Languages the active translator offers, for the popup's own pickers.
     *
     * The pair used to be shown as static text, so retargeting a translation meant opening the
     * main window — a context switch for the most common follow-up thought there is.
     */
    val availableLanguages: List<LanguageCode> = emptyList(),
    /** Detected source, shown beside Auto so the picker says what it settled on. */
    val detectedSourceLanguage: LanguageCode? = null,

    val translatorSelectorState: QuickTranslateSelectorState,
    val actionsState: QuickTranslateActionsState,
    val config: DialogConfig,
    val strings: DialogStrings
) : UiState

/**
 * Configuration for how the dialog should behave physically. This is a subset of your main Configuration.
 */
data class DialogConfig(
    val font: FontConfig,
    val fallbackFont: FontConfig,
    val autoSizeEnabled: Boolean,
    val autoPositionEnabled: Boolean,
    val transparencyPercentage: Int,
    val idleTimeoutSeconds: Int = 3,
    /** Whether pressing outside the popup dismisses it; pinning overrides this. */
    val closeOnClickOutside: Boolean = true,
    val lastKnownSize: Size,
    val lastKnownPosition: Position
)

/** State for the translator selector within the dialog. */
data class QuickTranslateSelectorState(
    val availableTranslators: List<ServiceInfo>,
    val selectedTranslatorId: String?,
)

/** State for the action buttons in the dialog's title bar. */
data class QuickTranslateActionsState(
    val canCopy: Boolean,
    val canListen: Boolean
)

/** All user-facing strings for the dialog. */
data class DialogStrings(
    val copyTooltip: String,
    val closeTooltip: String,
    val listenTooltip: String,
    val stopListeningTooltip: String,
    val pinTooltip: String,
    val unpinTooltip: String,
    val swapTooltip: String = "",
    val loadingText: String
)
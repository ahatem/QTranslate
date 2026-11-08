package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.Serializable

/**
 * What happens after the user finishes drawing a screen-capture selection.
 *
 * This is deliberately one enum rather than several booleans: the choices are mutually
 * exclusive, and the persisted value has to keep meaning the same thing when new choices are
 * added. Existing installations keep the historic action toolbar through [SHOW_ACTIONS].
 */
@Serializable
enum class OcrPostSelectionAction {
    /** Keep the capture overlay open and show the action toolbar. */
    SHOW_ACTIONS,

    /** Recognize the selection, then translate it. */
    TRANSLATE,

    /** Recognize the selection and copy the text, without translating. */
    COPY_TEXT,

    /** Put the selected image on the clipboard without running OCR at all. */
    COPY_IMAGE,

    /** Open the OCR Workspace on the captured image. */
    OPEN_WORKSPACE
}

/** Where an OCR workflow that translates should show its result. */
@Serializable
enum class OcrTranslationDestination {
    /** Use the canonical main-window translation path. */
    MAIN_WINDOW,

    /** Use the canonical Quick Translate popup path. */
    QUICK_TRANSLATE,

    /** Open the OCR Workspace and translate there. */
    OCR_WORKSPACE
}

/**
 * Persistent preferences for screen OCR.
 *
 * [recognitionLanguage] is deliberately a string: language tags already use this representation
 * everywhere else in [Configuration], and the empty value has a meaning of its own. It means
 * "follow the current translation source language", which is what the historical OCR flow did;
 * `"auto"` asks a capable provider to detect the language, and any other tag is matched against
 * the selected provider's declared languages.
 */
@Serializable
data class OcrSettings(
    val postSelectionAction: OcrPostSelectionAction = OcrPostSelectionAction.SHOW_ACTIONS,
    val translationDestination: OcrTranslationDestination = OcrTranslationDestination.MAIN_WINDOW,
    val recognitionLanguage: String = "",
    val removeLineBreaks: Boolean = false,
    val showWorkspaceAnnotations: Boolean = true
)

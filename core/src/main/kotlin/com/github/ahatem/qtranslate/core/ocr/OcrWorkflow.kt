package com.github.ahatem.qtranslate.core.ocr

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.OcrPostSelectionAction
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination

/**
 * What the user asked the OCR entry point to do before the selection is drawn.
 *
 * [DEFAULT] follows the saved post-selection action. The others are explicit overrides exposed
 * as hotkeys; they never mutate the saved preference.
 */
enum class OcrWorkflowCommand {
    DEFAULT,
    SHOW_ACTIONS,
    TRANSLATE,
    TRANSLATE_QUICK,
    TRANSLATE_MAIN,
    OPEN_WORKSPACE,
    COPY_TEXT,
    COPY_IMAGE
}

/** The resolved action and, when translation is involved, its forced destination. */
data class ResolvedOcrWorkflow(
    val action: OcrPostSelectionAction,
    /** Non-null only for an explicit destination override. */
    val destinationOverride: OcrTranslationDestination? = null,
)

/**
 * Resolves a command against [settings] without side effects.
 *
 * This is the single place where "explicit hotkey wins over saved preference" is expressed, so
 * the capture dialog, tests and any future entry point cannot disagree about it.
 */
fun resolveOcrWorkflow(command: OcrWorkflowCommand, settings: OcrSettings): ResolvedOcrWorkflow =
    when (command) {
        OcrWorkflowCommand.DEFAULT -> ResolvedOcrWorkflow(settings.postSelectionAction)
        OcrWorkflowCommand.SHOW_ACTIONS -> ResolvedOcrWorkflow(OcrPostSelectionAction.SHOW_ACTIONS)
        OcrWorkflowCommand.TRANSLATE -> ResolvedOcrWorkflow(OcrPostSelectionAction.TRANSLATE)
        OcrWorkflowCommand.TRANSLATE_QUICK -> ResolvedOcrWorkflow(
            OcrPostSelectionAction.TRANSLATE,
            OcrTranslationDestination.QUICK_TRANSLATE,
        )
        OcrWorkflowCommand.TRANSLATE_MAIN -> ResolvedOcrWorkflow(
            OcrPostSelectionAction.TRANSLATE,
            OcrTranslationDestination.MAIN_WINDOW,
        )
        OcrWorkflowCommand.OPEN_WORKSPACE -> ResolvedOcrWorkflow(OcrPostSelectionAction.OPEN_WORKSPACE)
        OcrWorkflowCommand.COPY_TEXT -> ResolvedOcrWorkflow(OcrPostSelectionAction.COPY_TEXT)
        OcrWorkflowCommand.COPY_IMAGE -> ResolvedOcrWorkflow(OcrPostSelectionAction.COPY_IMAGE)
    }

/** The workflow command a global OCR hotkey represents, or null for an unrelated action. */
fun HotkeyAction.ocrWorkflowCommand(): OcrWorkflowCommand? = when (this) {
    HotkeyAction.OPEN_OCR -> OcrWorkflowCommand.DEFAULT
    HotkeyAction.OCR_SHOW_ACTIONS -> OcrWorkflowCommand.SHOW_ACTIONS
    HotkeyAction.OCR_TRANSLATE -> OcrWorkflowCommand.TRANSLATE
    HotkeyAction.OCR_TRANSLATE_QUICK -> OcrWorkflowCommand.TRANSLATE_QUICK
    HotkeyAction.OCR_TRANSLATE_MAIN -> OcrWorkflowCommand.TRANSLATE_MAIN
    HotkeyAction.OCR_OPEN_WORKSPACE -> OcrWorkflowCommand.OPEN_WORKSPACE
    HotkeyAction.OCR_COPY_TEXT -> OcrWorkflowCommand.COPY_TEXT
    HotkeyAction.OCR_COPY_IMAGE -> OcrWorkflowCommand.COPY_IMAGE
    else -> null
}

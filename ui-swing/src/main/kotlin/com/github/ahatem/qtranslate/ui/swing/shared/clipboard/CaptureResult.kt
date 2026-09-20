package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

/**
 * The outcome of one selection capture, without overloading empty text.
 *
 * Empty string used to mean "nothing selected", "copy failed", "monitor unavailable" and
 * "restore failed" all at once, which is exactly how a failed Copy opened Image Search with
 * an empty query. Callers route on this type instead: only [Success] and deliberate
 * [NoUsableText] handling may dispatch user-visible actions, never [Failed].
 *
 * None of these states can attribute a clipboard change to a specific cause. The change
 * monitor only proves the clipboard's generation advanced after the capture token was taken;
 * it cannot prove that *this* Copy attempt is what advanced it, as opposed to another process,
 * a clipboard manager, or an unrelated user action racing inside the same short window. Every
 * state below is worded to the evidence actually available, not to what is merely likely.
 *
 * [NoUsableText] is reserved for the one case with an observed clipboard change: the
 * generation token advanced after Copy was attempted, yet what could be read back was
 * unusable. It reports what was observed on the clipboard, not a fact about the source
 * application's selection. A capture that never observes a confirmed clipboard change, or
 * whose Copy injection is itself known to have failed, is weaker evidence still — those land
 * in [Failed] with [CaptureFailure.COPY_UNCONFIRMED] or [CaptureFailure.COPY_INJECTION_FAILED]
 * instead of being guessed as [NoUsableText].
 *
 * Failures carry a category only, never clipboard contents.
 */
sealed interface CaptureResult {

    /**
     * Usable text was read from the clipboard after its generation advanced following a Copy
     * attempt, and the clipboard was restored behind it. As with every state here, the advance
     * is not proof that this Copy attempt (rather than a racing external actor) produced the
     * text; see the class doc.
     */
    data class Success(val text: String) : CaptureResult

    /**
     * The clipboard's generation advanced after Copy was attempted, but nothing usable could be
     * read back. This reports what was observed on the clipboard; it does not establish that
     * the source application had no selection, and it does not establish that this Copy attempt
     * (rather than a racing external actor) is what caused the advance.
     */
    data object NoUsableText : CaptureResult

    /** The capture could not run, complete, or be confirmed; the selection must not be dispatched. */
    data class Failed(val reason: CaptureFailure) : CaptureResult
}

/** Why a capture failed, without any captured content attached. */
enum class CaptureFailure {
    /** Trigger chord or contaminating modifiers never released within the bound. */
    NEUTRALIZATION_TIMEOUT,
    /** The original clipboard could not be snapshotted, so no Copy was attempted. */
    SNAPSHOT_FAILED,
    /** Change observation was unavailable, so no Copy was attempted. */
    MONITOR_UNAVAILABLE,
    /** The clipboard could not be restored; the selection is withheld. */
    RESTORE_FAILED,
    /** The copy/read round-trip itself errored. */
    CAPTURE_ERROR,
    /**
     * The configured [com.github.ahatem.qtranslate.ui.swing.main.CopyInjector] reported that
     * Copy definitely did not go through (no fallback available, or the fallback also failed).
     * A failed injection proves nothing about whether text was selected.
     */
    COPY_INJECTION_FAILED,
    /**
     * Copy injection was attempted and did not report a definite failure, but no clipboard
     * generation change was observed before the capture window elapsed. This is silence, not
     * evidence: the target may have ignored Copy, delivery may have been refused invisibly, or
     * the change simply may not have landed yet. It must never be read as a proven no-selection.
     */
    COPY_UNCONFIRMED,
}

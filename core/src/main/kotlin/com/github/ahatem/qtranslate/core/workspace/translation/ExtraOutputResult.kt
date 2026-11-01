package com.github.ahatem.qtranslate.core.workspace.translation

/**
 * Why a secondary output could not be produced.
 *
 * These are domain outcomes, not presentation text: the UI/localization layer resolves the
 * user-visible sentence for each case. [Provider] may carry a provider-supplied summary as a
 * parameter, but it is never assembled into a final sentence in core.
 */
sealed interface ExtraOutputFailure {
    /** No translator is active, so the request cannot be attempted at all. */
    data object NoTranslator : ExtraOutputFailure

    /** No summarizer is active. */
    data object NoSummarizer : ExtraOutputFailure

    /** No rewriter is active. */
    data object NoRewriter : ExtraOutputFailure

    /** The operation did not answer within the host's bound. */
    data object Timeout : ExtraOutputFailure

    /** The requested direction cannot be expressed, e.g. backward translation into Auto. */
    data object InvalidTarget : ExtraOutputFailure

    /**
     * The provider reported a failure. [summary] is the provider's own short summary, kept as a
     * parameter for the UI; it is not turned into a sentence here.
     */
    data class Provider(val summary: String? = null) : ExtraOutputFailure

    /** An unexpected exception escaped the operation. */
    data object Unexpected : ExtraOutputFailure
}

/**
 * Outcome of one secondary-output request.
 *
 * A secondary output can legitimately be empty, so "finished with nothing" and "failed" cannot
 * share the empty string. [Failure] carries a typed reason; the text is never the error message,
 * which keeps copy, speech and history from treating a failure as content.
 */
sealed interface ExtraOutputResult {
    data class Success(val text: String) : ExtraOutputResult
    data class Failure(val reason: ExtraOutputFailure) : ExtraOutputResult
}

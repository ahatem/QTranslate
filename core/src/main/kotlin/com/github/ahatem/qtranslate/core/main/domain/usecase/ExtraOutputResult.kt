package com.github.ahatem.qtranslate.core.main.domain.usecase

/**
 * Outcome of one secondary-output request.
 *
 * A secondary output can legitimately be empty, so "finished with nothing" and "failed" cannot
 * share the empty string. [Failure] carries the localized status summary for display; the text is
 * never the error message, which keeps copy, speech and history from treating a failure as content.
 */
sealed interface ExtraOutputResult {
    data class Success(val text: String) : ExtraOutputResult
    data class Failure(val message: String) : ExtraOutputResult
}

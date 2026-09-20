package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

/**
 * Result of capturing selected text.
 *
 * Clipboard changes cannot be attributed to this capture attempt alone.
 */
sealed interface CaptureResult {

    data class Success(val text: String) : CaptureResult

    data object NoUsableText : CaptureResult

    data class Failed(val reason: CaptureFailure) : CaptureResult
}

enum class CaptureFailure {
    NEUTRALIZATION_TIMEOUT,
    SNAPSHOT_FAILED,
    MONITOR_UNAVAILABLE,
    RESTORE_FAILED,
    CAPTURE_ERROR,

    /** Copy injection definitely failed. */
    COPY_INJECTION_FAILED,

    /** Copy was attempted, but no clipboard change was observed in time. */
    COPY_UNCONFIRMED,
}

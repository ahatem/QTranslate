package com.github.ahatem.qtranslate.ui.swing.main.input

import io.github.ahatem.qinput.QInputException

/**
 * `QIP_*` native status codes used to judge whether a chord-send failure permits falling back
 * to Robot injection.
 *
 * Only [INVALID_ARGUMENT], [UNSUPPORTED], and [INJECTION_NOT_DELIVERED] certify both zero
 * delivery and that a retry is safe. [INJECTION_UNCERTAIN] may have partially delivered the
 * chord, and [BACKEND_ERROR] can mean a physically-held chord key, where Robot would recreate
 * the same conflict.
 */
internal object NativeInjectionStatus {
    /** Validation failed before any keyboard input event was emitted. */
    const val INVALID_ARGUMENT = 1

    /** Excluded from [permitsFallback]; see the class doc. */
    const val BACKEND_ERROR = 2

    /** The backend, or a requested key/modifier, cannot be represented; nothing was sent. */
    const val UNSUPPORTED = 5

    /** A prefix of the chord may already have reached the target; never safe to retry. */
    const val INJECTION_UNCERTAIN = 7

    /** Certifies zero delivery and that a retry is safe. */
    const val INJECTION_NOT_DELIVERED = 8

    fun permitsFallback(code: Int): Boolean =
        code == INVALID_ARGUMENT || code == UNSUPPORTED || code == INJECTION_NOT_DELIVERED
}

/** See [NativeInjectionStatus]'s class doc for what makes a failure safe to retry. */
internal fun QInputException.permitsFallback(): Boolean =
    NativeInjectionStatus.permitsFallback(nativeCode())

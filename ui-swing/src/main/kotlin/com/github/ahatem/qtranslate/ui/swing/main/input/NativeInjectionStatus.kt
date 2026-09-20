package com.github.ahatem.qtranslate.ui.swing.main.input

import io.github.ahatem.qinput.QInputException

/**
 * `QIP_*` native status codes relevant to judging whether a [GlobalInputBackend.sendChord]
 * failure permits falling back to another injection mechanism (Robot). Mirrors the subset of
 * `qinput.h` this decision needs; kept narrow rather than a full enum of every status, most of
 * which are irrelevant here.
 *
 * **Delivery certainty and fallback permission are related but distinct questions, and this
 * object answers the second one, not the first.** A physically-held main chord key is the
 * concrete example where they diverge: native injection provably delivered zero events — the
 * same fact true of [INJECTION_NOT_DELIVERED] — yet falling back to Robot is *not* safe, because
 * Robot would recreate the identical ownership conflict against the same physically-held key.
 * [permitsFallback] therefore is not "did delivery prove zero", it is "is this specific,
 * documented failure cause one where an alternate injection attempt is known to be safe".
 *
 * The safe set is exactly [INVALID_ARGUMENT], [UNSUPPORTED] (both fail before any keyboard input
 * event is emitted, so nothing downstream could have been touched) and [INJECTION_NOT_DELIVERED]
 * (the native backend's own explicit certification that both zero delivery occurred *and* nothing
 * about the failure cause makes an alternate attempt unsafe). Everything else — in particular
 * [INJECTION_UNCERTAIN] and, deliberately, generic [BACKEND_ERROR] — must be treated as
 * fallback-forbidden. [INJECTION_UNCERTAIN] is forbidden because a second attempt could duplicate
 * input a prior, partially-delivered chord already sent. [BACKEND_ERROR] is forbidden for a
 * different reason: it carries no delivery-certainty guarantee at all. Some of its causes have an
 * unknown delivery state; others — a physically-held main chord key, for instance — do prove zero
 * delivery but still forbid fallback, because retrying would recreate the same conflict the
 * backend declined to risk. `BACKEND_ERROR` therefore certifies neither delivery certainty nor
 * fallback permission, and the policy here is to never treat it as safe to retry regardless of
 * which specific cause produced it.
 */
internal object NativeInjectionStatus {
    /** `qip_send_chord` validation failed before any keyboard input event was emitted. */
    const val INVALID_ARGUMENT = 1

    /**
     * A generic backend failure, deliberately excluded from [permitsFallback]. This code does
     * not certify a single delivery state: some of its causes leave delivery genuinely unknown,
     * while others — a physically-held main chord key, for instance — do prove zero delivery but
     * still forbid fallback, because retrying through Robot would recreate the exact conflict the
     * backend declined to risk. Proven zero delivery alone is therefore not enough to grant retry
     * permission. The one failure that both proves zero delivery *and* is safe to retry is
     * reported as the distinct [INJECTION_NOT_DELIVERED] rather than this code.
     */
    const val BACKEND_ERROR = 2

    /** The backend, or a requested key/modifier, cannot be represented; nothing was sent. */
    const val UNSUPPORTED = 5

    /**
     * Some prefix of the requested chord may have reached the target before native injection
     * gave up (Windows: a partial `SendInput`; macOS: a CoreGraphics event failing after an
     * earlier one in the same chord already posted). Retrying through a different mechanism could
     * duplicate already-delivered input, so callers must never fall back on this code.
     */
    const val INJECTION_UNCERTAIN = 7

    /**
     * Native injection certifies both that nothing was delivered and that falling back to another
     * injection mechanism is safe (Windows: a `SendInput` batch the OS reports accepted zero
     * events; macOS: the CoreGraphics event source itself could not be created, before any event
     * was posted; X11: the display connection itself could not be opened). See the class doc for
     * why plain [BACKEND_ERROR] is not treated the same way — not every zero-delivery condition
     * is reported as this status.
     */
    const val INJECTION_NOT_DELIVERED = 8

    /**
     * True only when [code] is a documented cause that both proves zero delivery and certifies an
     * alternate injection attempt (Robot) as safe.
     */
    fun permitsFallback(code: Int): Boolean =
        code == INVALID_ARGUMENT || code == UNSUPPORTED || code == INJECTION_NOT_DELIVERED
}

/**
 * True when this failure is a documented cause that permits falling back to another injection
 * mechanism (Robot) — not merely that this call itself delivered nothing. See
 * [NativeInjectionStatus]'s class doc for why those are different questions.
 */
internal fun QInputException.permitsFallback(): Boolean =
    NativeInjectionStatus.permitsFallback(nativeCode())

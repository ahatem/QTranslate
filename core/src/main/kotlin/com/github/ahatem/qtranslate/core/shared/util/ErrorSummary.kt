package com.github.ahatem.qtranslate.core.shared.util

import com.github.ahatem.qtranslate.api.plugin.ServiceError

/** How much of a failure message the status bar can show before it stops being readable. */
private const val SUMMARY_MAX_LENGTH = 120

/**
 * The first line of a failure, short enough for the status bar.
 *
 * A service that fails often says so at length: a stack trace, a page of provider HTML, a JSON
 * error body. Only the first line carries the part a reader can act on, and the cap keeps a
 * provider that puts everything on one line from filling the bar anyway. The full text is still
 * logged, and the error detail popup still shows it.
 */
fun ServiceError.shortSummary(): String =
    message.lineSequence().firstOrNull()?.take(SUMMARY_MAX_LENGTH) ?: UNKNOWN_ERROR

/** As above, for a raw exception, whose message may be absent entirely. */
fun Throwable.shortSummary(): String =
    message?.lineSequence()?.firstOrNull()?.take(SUMMARY_MAX_LENGTH) ?: UNKNOWN_ERROR

private const val UNKNOWN_ERROR = "Unknown error"

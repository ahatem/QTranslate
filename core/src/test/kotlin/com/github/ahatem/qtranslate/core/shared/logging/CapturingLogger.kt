package com.github.ahatem.qtranslate.core.shared.logging

import com.github.ahatem.qtranslate.api.core.Logger

/**
 * Records every message and every logged throwable.
 *
 * Privacy tests assert that sentinel strings — user text, provider bodies, URLs carrying keys —
 * never reach a captured log line, including through the throwable a call site hands to
 * [Logger.error], which a real backend prints into the log.
 */
internal class CapturingLogger : Logger {
    val messages = mutableListOf<String>()
    val loggedThrowables = mutableListOf<String>()

    override fun debug(message: String) {
        messages += message
    }

    override fun info(message: String) {
        messages += message
    }

    override fun warn(message: String) {
        messages += message
    }

    override fun error(message: String, error: Throwable?) {
        messages += message
        error?.let { loggedThrowables += it.toString() }
    }
}

internal class CapturingLoggerFactory(
    private val logger: CapturingLogger = CapturingLogger()
) : LoggerFactory {
    override fun getLogger(name: String): Logger = logger
}

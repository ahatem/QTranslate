package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.core.Logger

/**
 * Records every message and every logged throwable, so plugin privacy tests can assert that
 * sentinel content (user text, provider bodies, endpoints carrying keys) never reaches a log
 * line — directly, or through the throwable a call site hands to [Logger.error], which a real
 * backend prints into the log.
 */
class CapturingLogger : Logger {
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

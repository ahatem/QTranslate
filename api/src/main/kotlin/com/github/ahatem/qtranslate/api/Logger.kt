package com.github.ahatem.qtranslate.api

/**
 * A simple logging facade provided by the core application to plugins.
 * Plugins should use this interface for all logging to ensure messages are
 * correctly routed and categorized within the main application.
 */
interface Logger {
    /** Logs a message at the DEBUG level, for detailed developer diagnostics. */
    fun debug(message: String)

    /** Logs a message at the INFO level, for general operational messages. */
    fun info(message: String)

    /** Logs a message at the WARN level, for potential issues that are not errors. */
    fun warn(message: String)

    /** Logs an error message. */
    fun error(message: String)

    /** Logs an error message along with a stack trace from a Throwable. */
    fun error(message: String, error: Throwable)
}
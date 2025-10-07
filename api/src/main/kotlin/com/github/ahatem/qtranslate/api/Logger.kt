package com.github.ahatem.qtranslate.api

/**
 * A lightweight logging facade provided by the core application to plugins.
 *
 * Plugins must use this interface for all logging to ensure messages are consistently routed,
 * formatted, and categorized within the core application's logging system. This abstraction
 * allows the core to manage log output (e.g., to files, consoles, or external services) while
 * providing plugins with a simple, unified API for logging.
 *
 * ### Threading Model
 * All methods in this interface are thread-safe and can be called from any thread, including
 * the dedicated background thread used for plugin lifecycle methods or other plugin-specific
 * threads. Implementations are guaranteed to handle concurrency safely.
 *
 * ### Usage Guidelines
 * - Use the appropriate log level for the context: `debug` for detailed diagnostics, `info`
 *   for general operational messages, `warn` for potential issues, and `error` for critical
 *   failures.
 * - Avoid sensitive information (e.g., API keys, user data) in log messages, as they may be
 *   written to persistent storage or external systems.
 * - Keep messages concise yet descriptive to aid debugging and monitoring.
 *
 * @see PluginContext
 */
interface Logger {
    /**
     * Logs a message at the DEBUG level for detailed developer diagnostics.
     *
     * Use this level for verbose information useful during development or troubleshooting,
     * such as intermediate states, detailed configurations, or low-level operation traces.
     * These messages are typically disabled in production environments.
     *
     * @param message A concise, descriptive message explaining the event or state.
     */
    fun debug(message: String)

    /**
     * Logs a message at the INFO level for general operational messages.
     *
     * Use this level to record significant events in the plugin's lifecycle or operation,
     * such as successful initialization, service activation, or completion of a major task.
     * These messages are typically enabled in production for monitoring purposes.
     *
     * @param message A concise, descriptive message summarizing the event.
     */
    fun info(message: String)

    /**
     * Logs a message at the WARN level for potential issues that are not critical errors.
     *
     * Use this level to indicate conditions that might lead to problems, such as deprecated
     * API usage, transient network issues, or invalid but recoverable settings. These
     * messages help identify areas that may require attention without indicating a failure.
     *
     * @param message A concise, descriptive message detailing the potential issue.
     */
    fun warn(message: String)

    /**
     * Logs a message at the ERROR level for critical failures, optionally with a stack trace.
     *
     * Use this level to report unrecoverable errors that prevent normal operation, such as
     * failed API calls, invalid configurations, or resource unavailability. If a [Throwable]
     * is provided, its stack trace will be included in the log output for debugging purposes.
     *
     * @param message A concise, descriptive message explaining the error condition.
     * @param error An optional [Throwable] providing additional context, such as a stack trace.
     *              Defaults to `null` if no exception is available.
     */
    fun error(message: String, error: Throwable? = null)
}
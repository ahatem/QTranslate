package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import org.slf4j.LoggerFactory as Slf4jFactory

/**
 * The application's [LoggerFactory], backed by SLF4J and Logback.
 *
 * Configured by `logback.xml` in the app resources: console output as well as a file that rolls
 * daily and is kept for thirty days. Both, deliberately — the console is what a developer watches,
 * and the file is what a user can attach to a bug report.
 *
 * ### The log directory is set by the caller
 * `logback.xml` reads it from the `logDir` system property, which [main] sets from the resolved
 * app data directory. Logback configures itself on the first logger request, so anything that
 * asks for a logger before that property is set sends the whole session to the relative fallback
 * path instead.
 */
class LogbackLoggerFactory : LoggerFactory {
    override fun getLogger(name: String): Logger = LogbackLogger(Slf4jFactory.getLogger(name))
}

private class LogbackLogger(private val delegate: org.slf4j.Logger) : Logger {
    override fun debug(message: String)                    = delegate.debug(message)
    override fun info(message: String)                     = delegate.info(message)
    override fun warn(message: String)                     = delegate.warn(message)
    override fun error(message: String, error: Throwable?) =
        if (error != null) delegate.error(message, error) else delegate.error(message)
}
package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import org.slf4j.LoggerFactory as Slf4jFactory

/**
 * Production [LoggerFactory] backed by SLF4J + Logback.
 *
 * Logback is configured via `logback.xml` in the app resources.
 * That file controls:
 * - Console output format and colours
 * - Rolling file appender (daily rotation, 30-day retention)
 * - Log level per package
 *
 * Swap this for [ConsoleLoggerFactory] during unit tests or in environments
 * where Logback is not on the classpath.
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
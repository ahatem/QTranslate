package com.github.ahatem.qtranslate.core.testing

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory

object SilentLogger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}

object SilentLoggerFactory : LoggerFactory {
    override fun getLogger(name: String): Logger = SilentLogger
}

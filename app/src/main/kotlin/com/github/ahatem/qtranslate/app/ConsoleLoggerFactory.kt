package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A [LoggerFactory] that writes coloured, timestamped log lines to stdout.
 *
 * Intended for development and debugging. Replace with a file-backed or
 * framework-backed implementation (SLF4J, Log4j etc.) for production builds
 * by swapping the factory in [buildDependencies] — no other code changes needed.
 *
 * @property minLogLevel The minimum severity to emit. Messages below this level
 *   are silently discarded.
 */
class ConsoleLoggerFactory(
    private val minLogLevel: LogLevel = LogLevel.INFO
) : LoggerFactory {

    enum class LogLevel(val priority: Int, val ansiCode: String) {
        DEBUG(0, "\u001B[34m"),  // blue
        INFO (1, "\u001B[32m"),  // green
        WARN (2, "\u001B[33m"),  // yellow
        ERROR(3, "\u001B[31m")   // red
    }

    override fun getLogger(name: String): Logger =
        ConsoleLogger(name, minLogLevel)

    private class ConsoleLogger(
        private val name: String,
        private val minLevel: LogLevel
    ) : Logger {

        private companion object {
            const val RESET = "\u001B[0m"
            val TIME_FMT: DateTimeFormatter =
                DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        }

        private fun emit(level: LogLevel, message: String, error: Throwable? = null) {
            if (level.priority < minLevel.priority) return
            synchronized(System.out) {
                val ts     = LocalDateTime.now().format(TIME_FMT)
                val thread = Thread.currentThread().name
                val tag    = "${level.ansiCode}[${level.name}]$RESET"
                println("[$ts] [$thread] $tag [$name] $message")
                error?.printStackTrace(System.out)
            }
        }

        override fun debug(message: String)                    = emit(LogLevel.DEBUG, message)
        override fun info(message: String)                     = emit(LogLevel.INFO,  message)
        override fun warn(message: String)                     = emit(LogLevel.WARN,  message)
        override fun error(message: String, error: Throwable?) = emit(LogLevel.ERROR, message, error)
    }
}
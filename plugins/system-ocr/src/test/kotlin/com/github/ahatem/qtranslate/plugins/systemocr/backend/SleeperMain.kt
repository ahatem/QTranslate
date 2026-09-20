package com.github.ahatem.qtranslate.plugins.systemocr.backend

import java.io.File

/**
 * A minimal main started as a real child JVM by [ProcessRunnerTest], so the runner is exercised
 * against an actual process without depending on shell utilities.
 *
 * `echo` prints a known line; `sleep` writes its PID to a file and then sleeps.
 */
object SleeperMain {

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.getOrNull(0)) {
            "echo" -> println("hello-from-helper")
            "sleep" -> {
                args.getOrNull(1)?.let { File(it).writeText(ProcessHandle.current().pid().toString()) }
                Thread.sleep(args.getOrNull(2)?.toLongOrNull() ?: 30_000L)
            }
            else -> Thread.sleep(30_000L)
        }
    }
}

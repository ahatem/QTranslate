package com.github.ahatem.qtranslate.ui.swing.testing

import com.github.ahatem.qtranslate.api.core.Logger
import javax.swing.SwingUtilities

/** Runs [block] on the EDT, waits for it and rethrows anything it threw. */
fun <T> onEdt(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}

object SilentLogger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}

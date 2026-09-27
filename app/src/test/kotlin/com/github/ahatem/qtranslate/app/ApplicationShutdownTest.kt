package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationShutdownTest {
    private class Log : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private class Recorder {
        private val lock = Any()
        val order = mutableListOf<String>()
        fun record(step: String) {
            synchronized(lock) { order += step }
        }
    }

    private fun shutdown(
        recorder: Recorder,
        exited: CountDownLatch,
        persistWindowBounds: suspend (Size, Position) -> Unit = { _, _ -> recorder.record("bounds") }
    ): ApplicationShutdown = ApplicationShutdown(
        persistWindowBounds = persistWindowBounds,
        shutdownMainStore = { recorder.record("mainStore") },
        shutdownPluginManager = { recorder.record("pluginManager") },
        closeHostHttpClient = { recorder.record("httpClient") },
        cancelAppScope = { recorder.record("appScope") },
        logger = Log(),
        exit = { recorder.record("exit"); exited.countDown() }
    )

    @Test fun `runs every step exactly once in order and exits last`() {
        val recorder = Recorder()
        val exited = CountDownLatch(1)

        shutdown(recorder, exited).requestShutdown(Size(800, 600), Position(10, 20))

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("bounds", "mainStore", "pluginManager", "httpClient", "appScope", "exit"),
            recorder.order
        )
    }

    @Test fun `a second and third request are ignored once shutdown has started`() {
        val recorder = Recorder()
        val exited = CountDownLatch(1)
        val application = shutdown(recorder, exited)

        application.requestShutdown(Size(800, 600), Position(10, 20))
        application.requestShutdown(Size(1, 1), Position(1, 1))
        application.requestShutdown(Size(2, 2), Position(2, 2))

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(1, recorder.order.count { it == "mainStore" })
        assertEquals(1, recorder.order.count { it == "pluginManager" })
        assertEquals(1, recorder.order.count { it == "exit" })
    }

    @Test fun `a failing step does not prevent later steps or exit`() {
        val recorder = Recorder()
        val exited = CountDownLatch(1)
        val application = shutdown(
            recorder,
            exited,
            persistWindowBounds = { _, _ -> recorder.record("bounds"); error("disk full") }
        )

        application.requestShutdown(Size(800, 600), Position(10, 20))

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("bounds", "mainStore", "pluginManager", "httpClient", "appScope", "exit"),
            recorder.order
        )
    }
}

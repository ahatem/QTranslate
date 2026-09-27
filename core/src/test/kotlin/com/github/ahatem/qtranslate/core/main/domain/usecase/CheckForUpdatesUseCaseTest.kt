package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CheckForUpdatesUseCase.invoke] is the single gate an automatic startup check and the manual
 * "Check for Updates" menu action both go through: this covers that gate directly, since
 * neither Main.kt's one-shot startup dispatch nor the Swing menu action are practical to
 * exercise in a unit test.
 */
class CheckForUpdatesUseCaseTest {
    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val loggerFactory = object : LoggerFactory {
        override fun getLogger(name: String): Logger = silentLogger
    }

    private fun releaseJson() = """
        {
          "tag_name": "v1.0.0",
          "name": "QTranslate 1.0.0",
          "body": "notes",
          "html_url": "https://github.com/ahatem/QTranslate/releases/tag/v1.0.0",
          "assets": []
        }
    """.trimIndent()

    private fun useCase(autoCheckForUpdates: Boolean, requestCount: AtomicInteger): CheckForUpdatesUseCase {
        val engine = MockEngine {
            requestCount.incrementAndGet()
            respond(
                content = releaseJson(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val updater = Updater("ahatem", "qtranslate", client, silentLogger)
        val settingsState = MutableStateFlow(Configuration.DEFAULT.copy(autoCheckForUpdates = autoCheckForUpdates))
        return CheckForUpdatesUseCase(
            currentVersion = "1.0.0",
            settingsState = settingsState,
            updater = updater,
            notificationBus = NotificationBus(),
            loggerFactory = loggerFactory
        )
    }

    @Test fun `automatic check occurs when the setting is enabled`() = runTest {
        val requestCount = AtomicInteger()
        var statusUpdates = 0

        useCase(autoCheckForUpdates = true, requestCount).invoke(
            onStatusUpdate = { _, _, _ -> statusUpdates++ },
            force = false
        )

        assertEquals(1, requestCount.get())
        assertEquals(1, statusUpdates)
    }

    @Test fun `automatic check does not occur when the setting is disabled`() = runTest {
        val requestCount = AtomicInteger()
        var statusUpdates = 0

        useCase(autoCheckForUpdates = false, requestCount).invoke(
            onStatusUpdate = { _, _, _ -> statusUpdates++ },
            force = false
        )

        assertEquals(0, requestCount.get())
        assertEquals(0, statusUpdates)
    }

    @Test fun `a forced check ignores the disabled setting`() = runTest {
        val requestCount = AtomicInteger()

        useCase(autoCheckForUpdates = false, requestCount).invoke(
            onStatusUpdate = { code, _, _ -> assertEquals(StatusCode.AlreadyUpToDate("1.0.0"), code) },
            force = true
        )

        assertEquals(1, requestCount.get())
    }
}

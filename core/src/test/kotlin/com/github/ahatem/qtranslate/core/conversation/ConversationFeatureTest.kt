package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateStringUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationFeatureTest {

    private lateinit var scope: CoroutineScope
    private lateinit var tempDir: File

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val loggerFactory = object : LoggerFactory {
        override fun getLogger(name: String) = logger
    }

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tempDir = Files.createTempDirectory("qt-conv-feature-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `isPlaybackActive becomes false within cooldown after stop`() {
        var currentTime = 1000L
        val activeServiceManager = ActiveServiceManager(MutableStateFlow(emptyMap()), MutableStateFlow(Configuration.DEFAULT))
        val feature = ConversationFeature(
            scope = scope,
            activeServiceManager = activeServiceManager,
            translateString = TranslateStringUseCase(activeServiceManager),
            appData = tempDir,
            loggerFactory = loggerFactory,
            systemBackend = WasapiLoopbackBackend(powershell = null),
            clock = { currentTime }
        )

        // Simulate 30s playback started at t=1000
        feature.markPlayback(30_000)
        assertTrue(feature.isPlaybackActive())

        // Stop early at t=2000 (after 1s)
        currentTime = 2000L
        feature.stop()

        // Within cooldown (cooldown is 1500ms -> active until 3500ms)
        currentTime = 3000L
        assertTrue(feature.isPlaybackActive())

        // Past cooldown (3501ms) -> must be false
        currentTime = 3501L
        assertFalse(feature.isPlaybackActive(), "Playback cooldown should have ended 1500ms after stop, not from 30s clip duration")
    }
}

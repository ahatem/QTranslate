package com.github.ahatem.qtranslate.ui.swing.conversation

import com.formdev.flatlaf.FlatDarkLaf
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.conversation.AudioBackend
import com.github.ahatem.qtranslate.core.conversation.AudioDevice
import com.github.ahatem.qtranslate.core.conversation.ConversationFeature
import com.github.ahatem.qtranslate.core.conversation.PcmStream
import com.github.ahatem.qtranslate.core.conversation.WasapiLoopbackBackend
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslateStringUseCase
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the Conversation Sessions window at several widths, with real-world device names, and
 * writes PNGs for a person to look at. Needs a display (run under xvfb-run) and is skipped unless
 * QT_SCREENSHOTS names the output folder, so it never runs in ordinary builds.
 */
class ConversationWindowScreenshotTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val devices = object : AudioBackend {
        override fun inputDevices() = listOf(
            "SteelSeries Sonar - Microphone (SteelSeries Sonar Virtual Audio Device)",
            "SteelSeries Sonar - Chat (SteelSeries Sonar Virtual Audio Device)",
            "Headset Earphone (HyperX Virtual Surround Sound)"
        ).map { AudioDevice(it, it) }

        override fun open(device: AudioDevice): PcmStream = error("not recorded in a screenshot")
    }

    @Test
    fun `renders the window at several widths`() {
        val output = System.getenv("QT_SCREENSHOTS")
        assumeTrue(output != null, "set QT_SCREENSHOTS to an output folder to render screenshots")
        File(output!!).mkdirs()

        val appData = Files.createTempDirectory("qt-conversation-shots").toFile()
        val feature = ConversationFeature(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            activeServiceManager = ActiveServiceManager(MutableStateFlow(emptyMap()), MutableStateFlow(Configuration.DEFAULT)),
            translateString = TranslateStringUseCase(ActiveServiceManager(MutableStateFlow(emptyMap()), MutableStateFlow(Configuration.DEFAULT))),
            appData = appData,
            loggerFactory = object : LoggerFactory { override fun getLogger(name: String) = logger },
            microphoneBackend = devices,
            // Not supported here, so the call picker falls back to inputs that look like system audio.
            systemBackend = WasapiLoopbackBackend(powershell = null)
        )
        val localizer = LocalizationManager(appData, LanguageTomlParser(), logger)
        val languages = listOf(LanguageCode.AUTO, LanguageCode.ENGLISH, LanguageCode.ARABIC, LanguageCode("de"), LanguageCode("fr"))

        var window: ConversationWindow? = null
        SwingUtilities.invokeAndWait {
            FlatDarkLaf.setup()
            window = ConversationWindow(feature, ConversationStrings(localizer), { languages }, { LanguageCode.ENGLISH }, { false })
            window!!.open()
        }
        // Device loading runs off the EDT and comes back to it.
        Thread.sleep(3_000)

        listOf(780, 1000, 1300).forEach { width ->
            val image = BufferedImage(width, 620, BufferedImage.TYPE_INT_RGB)
            SwingUtilities.invokeAndWait {
                window!!.size = Dimension(width, 620)
                window!!.validate()
            }
            // Painted in a later event, as on screen, so layout passes queued by the resize have run.
            SwingUtilities.invokeAndWait { }
            SwingUtilities.invokeAndWait {
                window!!.validate()
                val graphics = image.createGraphics()
                window!!.contentPane.printAll(graphics)
                graphics.dispose()
            }
            val file = File(output, "conversation-$width.png")
            ImageIO.write(image, "png", file)
            assertTrue(file.length() > 0)
        }
        SwingUtilities.invokeAndWait { window!!.dispose() }
    }
}

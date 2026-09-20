package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.systemocr.backend.PathExecutableLocator
import com.github.ahatem.qtranslate.plugins.systemocr.backend.RealProcessRunner
import com.github.ahatem.qtranslate.plugins.systemocr.backend.SystemOcrBackends
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A real Windows.Media.Ocr run over a generated image, gated behind `SYSTEM_OCR_SMOKE=true`. The
 * report is written to `build/system-ocr-smoke-report.txt` (or `SYSTEM_OCR_SMOKE_REPORT`).
 */
class WindowsSystemOcrSmokeTest {

    private companion object {
        const val SMOKE_ENV = "SYSTEM_OCR_SMOKE"
        const val REPORT_ENV = "SYSTEM_OCR_SMOKE_REPORT"
        const val AUTO_REPORT_ENV = "SYSTEM_OCR_AUTO_SMOKE_REPORT"
    }

    @Test
    fun `the real Windows engine recognizes a generated image`() {
        if (System.getenv(SMOKE_ENV) != "true") {
            println("Windows OCR smoke test skipped; set $SMOKE_ENV=true to run it.")
            return
        }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            println("Windows OCR smoke test skipped: this is not Windows.")
            return
        }
        runBlocking { runSmokeTest() }
    }

    private suspend fun runSmokeTest() {
        val dataDirectory = Files.createTempDirectory("system-ocr-smoke").toFile()
        val backend = SystemOcrBackends.create(
            osName = "Windows 11",
            dataDirectory = dataDirectory,
            runner = RealProcessRunner(),
            locator = PathExecutableLocator,
        ).unwrap()

        val languages = backend.supportedLanguages().unwrap().languages
        val selected = languages.firstOrNull { it.tag.startsWith("en") }
            ?: languages.firstOrNull()
            ?: error("the Windows OCR engine reported no recognizer languages")
        val image = renderTestImage()

        val (firstResult, firstMillis) = measure { backend.recognize(image, selected) }
        val (secondResult, secondMillis) = measure { backend.recognize(image, selected) }
        val text = secondResult.fold(success = { it }, failure = { "" })

        val report = buildString {
            appendLine("System OCR - Windows smoke test")
            appendLine("engine              = ${backend.displayName}")
            appendLine("availableLanguages  = ${languages.map { it.tag }.sorted()}")
            appendLine("selectedLanguage    = ${selected.tag}")
            appendLine("firstCallMillis     = $firstMillis")
            appendLine("secondCallMillis    = $secondMillis")
            appendLine("firstStatus         = ${if (firstResult.isOk) "Ok" else "Err"}")
            appendLine("secondStatus        = ${if (secondResult.isOk) "Ok" else "Err"}")
            appendLine("output              = ${text.replace("\n", "\\n")}")
        }

        val target = System.getenv(REPORT_ENV)?.let { File(it) } ?: File("build/system-ocr-smoke-report.txt")
        target.parentFile?.mkdirs()
        target.writeText(report)
        println(report)

        assertTrue(secondResult.isOk, "the second recognition should have succeeded")
        assertTrue(text.contains("QTranslate", ignoreCase = true), "recognized text was '$text'")
        assertTrue(text.contains("12345"), "recognized text was '$text'")
    }

    /**
     * The default workflow: the host supplies `LanguageCode.AUTO` because the user has not chosen a
     * language yet. This has to recognize rather than refuse.
     */
    @Test
    fun `the default AUTO workflow recognizes without a chosen language`() {
        if (System.getenv(SMOKE_ENV) != "true") {
            println("AUTO workflow smoke test skipped; set $SMOKE_ENV=true to run it.")
            return
        }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            println("AUTO workflow smoke test skipped: this is not Windows.")
            return
        }
        runBlocking { runAutoWorkflow() }
    }

    private suspend fun runAutoWorkflow() {
        val dataDirectory = Files.createTempDirectory("system-ocr-auto").toFile()
        val backend = SystemOcrBackends.create(
            osName = "Windows 11",
            dataDirectory = dataDirectory,
            runner = RealProcessRunner(),
            locator = PathExecutableLocator,
        ).unwrap()
        val service = SystemOcrService(backend, FakePluginContext.SilentLogger)

        val supported = backend.supportedLanguages().unwrap()
        val platformTags = AutoLanguageResolver.platformPreferredTags()
        val selected = AutoLanguageResolver.resolve(supported.languages, platformTags)

        val image = renderTestImage()
        val (result, elapsedMillis) = measure { service.extractText(OCRRequest(image, LanguageCode.AUTO)) }
        val text = result.fold(success = { it.text }, failure = { "ERROR: ${it.message}" })

        val report = buildString {
            appendLine("System OCR - Windows AUTO workflow")
            appendLine("requestedLanguage  = auto")
            appendLine("detectsLanguage    = ${supported.detectsLanguage}")
            appendLine("installedLanguages = ${supported.languages.map { it.tag }.sorted()}")
            appendLine("platformLanguage   = $platformTags")
            appendLine("selectedRecognizer = ${selected?.tag}")
            appendLine("elapsedMillis      = $elapsedMillis")
            appendLine("status             = ${if (result.isOk) "Ok" else "Err"}")
            appendLine("output             = ${text.replace("\n", "\\n")}")
        }
        val target = System.getenv(AUTO_REPORT_ENV)?.let { File(it) } ?: File("build/system-ocr-auto-report.txt")
        target.parentFile?.mkdirs()
        target.writeText(report, Charsets.UTF_8)
        println(report)

        assertTrue(selected != null, "no installed recognizer to fall back to")
        assertTrue(result.isOk, "AUTO must recognize on a machine with an installed recognizer")
        assertTrue(text.contains("QTranslate", ignoreCase = true), "recognized text was '$text'")
        assertTrue(text.contains("12345"), "recognized text was '$text'")
    }

    private fun renderTestImage(): ImageData {
        val width = 640
        val height = 240
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.BLACK
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 56)
            graphics.drawString("QTranslate OCR Test", 24, 100)
            graphics.drawString("12345", 24, 190)
        } finally {
            graphics.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return ImageData(out.toByteArray(), "png", width, height)
    }

    private suspend fun <T> measure(block: suspend () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1_000_000
    }
}

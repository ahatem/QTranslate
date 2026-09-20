package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.backend.HelperProtocol
import com.github.ahatem.qtranslate.plugins.systemocr.backend.HelperResult
import com.github.ahatem.qtranslate.plugins.systemocr.backend.PathExecutableLocator
import com.github.ahatem.qtranslate.plugins.systemocr.backend.ProcessOutcome
import com.github.ahatem.qtranslate.plugins.systemocr.backend.ProcessRunner
import com.github.ahatem.qtranslate.plugins.systemocr.backend.RealProcessRunner
import com.github.ahatem.qtranslate.plugins.systemocr.backend.SystemOcrBackends
import com.github.michaelbull.result.Result
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A real Windows.Media.Ocr run over rendered Arabic, mixed and Latin lines. It writes the engine's
 * raw word order and the reconstructed text to a report.
 *
 * Gated behind `SYSTEM_OCR_RTL_SMOKE=true`; the rendered page is left in `build/rtl-smoke/`.
 */
class WindowsRtlOcrSmokeTest {

    private companion object {
        const val SMOKE_ENV = "SYSTEM_OCR_RTL_SMOKE"
        const val REPORT_ENV = "SYSTEM_OCR_RTL_SMOKE_REPORT"

        /** The reported Arabic line. */
        const val ARABIC = "رهييييب لن يعرف قيمته الا من يعشق الترمنال"

        /** Arabic with an embedded Latin run and a shortcut. */
        const val MIXED = "افتح Windows Terminal واضغط Ctrl + C"

        /** A left-to-right line, recognized on the same page. */
        const val ENGLISH = "QTranslate OCR Test"

        /** Arabic with a Latin word and a number. */
        const val MIXED_NUMBER = "افتح Windows 11 الآن"
    }

    @Test
    fun `the real Windows engine returns Arabic and mixed lines in logical order`() {
        if (System.getenv(SMOKE_ENV) != "true") {
            println("RTL smoke test skipped; set $SMOKE_ENV=true to run it.")
            return
        }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            println("RTL smoke test skipped: this is not Windows.")
            return
        }
        runBlocking { runSmokeTest() }
    }

    private suspend fun runSmokeTest() {
        val dataDirectory = Files.createTempDirectory("system-ocr-rtl-smoke").toFile()
        val capturing = CapturingRunner(RealProcessRunner())
        val backend = SystemOcrBackends.create(
            osName = "Windows 11",
            dataDirectory = dataDirectory,
            runner = capturing,
            locator = PathExecutableLocator,
        ).unwrap()

        val languages = backend.supportedLanguages().unwrap().languages
        val arabic = languages.firstOrNull { it.tag.startsWith("ar") }
            ?: error("no Arabic OCR recognizer is installed; installed languages: ${languages.map { it.tag }}")

        val page = renderPage()
        val startedAt = System.nanoTime()
        val logical = backend.recognize(page, arabic).unwrap()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        // The helper's payload holds the engine's own, unreordered text.
        val rawLines = capturing.results.lastOrNull()?.lines.orEmpty()

        val report = buildString {
            appendLine("System OCR - Windows RTL smoke test")
            appendLine("engine            = ${backend.displayName}")
            appendLine("requestedLanguage = ${arabic.tag}")
            appendLine("installedLanguages= ${languages.map { it.tag }.sorted()}")
            appendLine("elapsedMillis     = $elapsedMillis")
            appendLine("rawEngineText     = ${rawLines.joinToString("\\n") { it.text }}")
            appendLine("returnedText      = ${logical.replace("\n", "\\n")}")
            appendLine("expectedLine1     = $ARABIC")
            appendLine("expectedLine2     = $MIXED")
            appendLine("expectedLine3     = $ENGLISH")
            appendLine("expectedLine4     = $MIXED_NUMBER")
        }
        val target = System.getenv(REPORT_ENV)?.let { File(it) } ?: File("build/rtl-smoke/report.txt")
        target.parentFile?.mkdirs()
        target.writeText(report, Charsets.UTF_8)
        println(report)

        val lines = logical.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(4, lines.size, "expected four recognized lines, got: $logical")

        // Recognition was clean on this line, so it can be compared exactly.
        assertEquals(ARABIC, normalize(lines[0]))

        // The Latin line is untouched.
        assertEquals(ENGLISH, normalize(lines[2]))

        // The number must stay inside the Latin run. The engine misreads the last Arabic word on
        // this page, so the line is graded on order; OcrLineOrderTest covers the exact string.
        val numberLine = normalize(lines[3])
        assertTrue(numberLine.startsWith("افتح"), "expected the Arabic sentence to start logically: $numberLine")
        assertTrue(numberLine.contains("Windows 11"), "expected the number to stay in the Latin run: $numberLine")
        assertFalse(numberLine.contains("11 Windows"), "the number was dragged out of the Latin run: $numberLine")
        assertFalse(numberLine.endsWith("Windows 11"), "the Arabic tail was misplaced: $numberLine")

        // The Latin run must read left to right, and the sentence must begin with its Arabic
        // opening. The engine tokenized the shortcut oddly on this page, so only its position is
        // asserted; OcrLineOrderTest covers its internal order.
        val mixed = normalize(lines[1])
        assertTrue(mixed.startsWith("افتح"), "expected the Arabic sentence to start logically: $mixed")
        assertTrue(mixed.contains("Windows Terminal"), "expected the Latin run to stay left-to-right: $mixed")
        assertFalse(mixed.contains("Terminal Windows"), "expected no reversed Latin run: $mixed")

        // If the engine ever stops reporting RTL lines reversed, the reconstruction can go.
        val rawFirst = rawLines.firstOrNull()?.text?.let(::normalize)
        assertTrue(
            rawFirst != null && rawFirst != normalize(lines[0]),
            "expected the engine's raw order to differ from the logical order, but both were: $rawFirst",
        )
    }

    private fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    /** Four lines, each drawn as its own bidirectional paragraph. */
    private fun renderPage(): ImageData {
        val width = 1_100
        val height = 500
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.BLACK
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 44)
            graphics.drawString(ARABIC, 40, 100)
            graphics.drawString(MIXED, 40, 210)
            graphics.drawString(ENGLISH, 40, 320)
            graphics.drawString(MIXED_NUMBER, 40, 430)
        } finally {
            graphics.dispose()
        }

        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        val png = out.toByteArray()
        File("build/rtl-smoke").mkdirs()
        File("build/rtl-smoke/rtl-smoke-input.png").writeBytes(png)
        return ImageData(png, "png", width, height)
    }

    /** Keeps the helper's payload so the raw text can be reported. */
    private class CapturingRunner(private val delegate: ProcessRunner) : ProcessRunner {
        val results = mutableListOf<HelperResult>()

        override suspend fun run(
            command: List<String>,
            timeoutMillis: Long,
        ): Result<ProcessOutcome, ServiceError> {
            val outcome = delegate.run(command, timeoutMillis)
            outcome.fold(
                success = { value ->
                    command.argumentValue("-OutputPath")?.let { path ->
                        HelperProtocol.read(File(path), value).fold(
                            success = { result -> if (result.lines.isNotEmpty()) results += result },
                            failure = { },
                        )
                    }
                },
                failure = { },
            )
            return outcome
        }
    }
}

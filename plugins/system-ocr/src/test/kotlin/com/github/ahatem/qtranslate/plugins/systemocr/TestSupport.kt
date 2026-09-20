package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.backend.BackendLanguages
import com.github.ahatem.qtranslate.plugins.systemocr.backend.ProcessOutcome
import com.github.ahatem.qtranslate.plugins.systemocr.backend.ProcessRunner
import com.github.ahatem.qtranslate.plugins.systemocr.backend.SystemOcrBackend
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.fail

/**
 * Records every command it is asked to run and answers with a canned [ProcessOutcome]. Launch
 * failures come from the real process runner instead, which the launch-failure tests use.
 */
internal class FakeProcessRunner(
    private val handler: (List<String>) -> ProcessOutcome,
) : ProcessRunner {

    val commands: MutableList<List<String>> = mutableListOf()

    override suspend fun run(command: List<String>, timeoutMillis: Long): Result<ProcessOutcome, ServiceError> {
        commands += command
        return Ok(handler(command))
    }
}

/** The value that follows [flag] in an argument list, or `null`. */
internal fun List<String>.argumentValue(flag: String): String? {
    val index = indexOf(flag)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}

/** A helper that writes [json] to the file named by the command's [outputFlag]. */
internal fun helperWriting(json: String, outputFlag: String = "-OutputPath"): FakeProcessRunner =
    FakeProcessRunner { command ->
        command.argumentValue(outputFlag)?.let { File(it).writeText(json) }
        ProcessOutcome(exitCode = 0, stdout = "", stderr = "", timedOut = false)
    }

/** A helper fake that answers recognise and capabilities with different payloads. */
internal fun helperResponding(capabilities: String, recognition: String, outputFlag: String = "-OutputPath"): FakeProcessRunner =
    FakeProcessRunner { command ->
        val json = if (command.any { it == "capabilities" }) capabilities else recognition
        command.argumentValue(outputFlag)?.let { File(it).writeText(json) }
        ProcessOutcome(exitCode = 0, stdout = "", stderr = "", timedOut = false)
    }

/** A decodable image. */
internal fun imageData(
    width: Int = 80,
    height: Int = 40,
    text: String? = null,
    format: String = "png",
): ImageData {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        if (text != null) {
            graphics.color = Color.BLACK
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, maxOf(10, height / 3))
            graphics.drawString(text, 4, height / 2 + 4)
        }
    } finally {
        graphics.dispose()
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(image, format, out)
    return ImageData(out.toByteArray(), format, width, height)
}

/** Bytes that are not a decodable image. */
internal fun corruptImageData(): ImageData = ImageData(byteArrayOf(1, 2, 3, 4), "png", 10, 10)

/** The dimensions of encoded image bytes, for asserting what a helper received. */
internal fun encodedImageSize(bytes: ByteArray): Pair<Int, Int> {
    val decoded = ImageIO.read(bytes.inputStream())
    return decoded.width to decoded.height
}

/** A backend with scripted answers, for testing the service in isolation. */
internal class FakeBackend(
    private val onRecognize: suspend (ImageData, LanguageCode) -> Result<String, ServiceError> =
        { _, _ -> Ok("recognized text") },
    private val onLanguages: suspend () -> Result<BackendLanguages, ServiceError> =
        { Ok(BackendLanguages(setOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE), detectsLanguage = false)) },
    private val onValidate: suspend () -> Result<Unit, ServiceError> = { Ok(Unit) },
    override val displayName: String = "Fake Engine",
) : SystemOcrBackend {
    override suspend fun recognize(image: ImageData, language: LanguageCode): Result<String, ServiceError> =
        onRecognize(image, language)

    override suspend fun supportedLanguages(): Result<BackendLanguages, ServiceError> = onLanguages()

    override suspend fun validate(): Result<Unit, ServiceError> = onValidate()
}

/** Unwraps a successful result, failing the test on an unexpected error. */
internal fun <T> Result<T, ServiceError>.unwrap(): T =
    fold(success = { it }, failure = { fail("Expected success, but was $it") })

/** Unwraps a failed result, failing the test on an unexpected success. */
internal fun Result<*, ServiceError>.unwrapError(): ServiceError =
    fold(success = { fail("Expected a failure, but got $it") }, failure = { it })

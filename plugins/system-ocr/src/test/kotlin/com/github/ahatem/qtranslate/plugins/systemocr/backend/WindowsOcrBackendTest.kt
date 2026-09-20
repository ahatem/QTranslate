package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.FakeProcessRunner
import com.github.ahatem.qtranslate.plugins.systemocr.argumentValue
import com.github.ahatem.qtranslate.plugins.systemocr.corruptImageData
import com.github.ahatem.qtranslate.plugins.systemocr.encodedImageSize
import com.github.ahatem.qtranslate.plugins.systemocr.imageData
import com.github.ahatem.qtranslate.plugins.systemocr.unwrap
import com.github.ahatem.qtranslate.plugins.systemocr.unwrapError
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsOcrBackendTest {

    private val script: File = Files.createTempFile("windows-ocr", ".ps1").toFile()

    private fun backend(runner: ProcessRunner, powershell: String? = "powershell.exe") =
        WindowsOcrBackend(runner, script, powershell)

    /**
     * Answers capabilities and recognition differently. `onImageBytes` sees the image file while it
     * still exists, which is how the resize test observes what the helper was handed.
     */
    private fun helper(
        capabilities: String = CAPABILITIES,
        recognitionJson: String? = null,
        recognitionOutcome: ProcessOutcome = ProcessOutcome(0, "", "", false),
        onImageBytes: ((ByteArray) -> Unit)? = null,
    ) = FakeProcessRunner { command ->
        if (command.any { it == COMMAND_CAPABILITIES }) {
            command.argumentValue("-OutputPath")?.let { File(it).writeText(capabilities) }
            ProcessOutcome(0, "", "", false)
        } else {
            command.argumentValue("-ImagePath")?.let { path -> onImageBytes?.invoke(File(path).readBytes()) }
            if (recognitionJson != null) {
                command.argumentValue("-OutputPath")?.let { File(it).writeText(recognitionJson) }
            }
            recognitionOutcome
        }
    }

    @Test
    fun `recognize returns the recognized text`() = runBlocking {
        val backend = backend(helper(recognitionJson = """{"ok":true,"text":"Hello\nWorld","language":"en-US"}"""))

        assertEquals("Hello\nWorld", backend.recognize(imageData(), LanguageCode.ENGLISH).unwrap())
    }

    @Test
    fun `recognize returns an empty string when the engine finds no text`() = runBlocking {
        val backend = backend(helper(recognitionJson = """{"ok":true,"text":""}"""))

        assertEquals("", backend.recognize(imageData(), LanguageCode.ENGLISH).unwrap())
    }

    @Test
    fun `recognize maps an unsupported language`() = runBlocking {
        val backend = backend(helper(recognitionJson = """{"ok":false,"category":"unsupported_language","error":"not installed"}"""))

        val error = backend.recognize(imageData(), LanguageCode.KOREAN).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
        assertEquals(LanguageCode.KOREAN, error.language)
    }

    @Test
    fun `recognize rejects AUTO without starting a process`() = runBlocking {
        // Windows OCR recognizes with a chosen recognizer; it does not detect language, so AUTO is
        // refused rather than answered with a default.
        val runner = helper(recognitionJson = """{"ok":true,"text":"should not happen"}""")

        val error = backend(runner).recognize(imageData(), LanguageCode.AUTO).unwrapError()

        assertTrue(error is ServiceError.UnsupportedLanguageError)
        assertEquals(LanguageCode.AUTO, error.language)
        assertTrue(runner.commands.isEmpty(), "AUTO must be rejected before any process starts")
    }

    @Test
    fun `recognize maps a missing recognizer to a configuration error`() = runBlocking {
        val backend = backend(helper(recognitionJson = """{"ok":false,"category":"no_engine","error":"none"}"""))

        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.ConfigurationError)
    }

    @Test
    fun `recognize maps a malformed result to an invalid response`() = runBlocking {
        val backend = backend(helper(recognitionJson = "not json at all"))

        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.InvalidResponseError)
    }

    @Test
    fun `recognize reports a missing result file`() = runBlocking {
        // Capabilities written, recognition result missing, as a crashed helper would leave it.
        val backend = backend(helper(recognitionOutcome = ProcessOutcome(0, "", "boom", false)))

        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.InvalidResponseError)
    }

    @Test
    fun `recognize maps a timeout`() = runBlocking {
        val backend = backend(helper(recognitionOutcome = ProcessOutcome(-1, "", "", timedOut = true)))

        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.TimeoutError)
    }

    @Test
    fun `a corrupt image is rejected before any process is started`() = runBlocking {
        val runner = helper(recognitionJson = """{"ok":true,"text":"x"}""")

        assertTrue(backend(runner).recognize(corruptImageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.InvalidInputError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `recognize deletes its temporary files`() = runBlocking {
        val runner = helper(recognitionJson = """{"ok":true,"text":"x"}""")

        backend(runner).recognize(imageData(), LanguageCode.ENGLISH).unwrap()

        val recognition = runner.commands.single { !it.contains(COMMAND_CAPABILITIES) }
        assertFalse(File(recognition.argumentValue("-ImagePath")!!).exists(), "temporary image should be removed")
        assertFalse(File(recognition.argumentValue("-OutputPath")!!).exists(), "temporary result should be removed")
    }

    @Test
    fun `the language hint is passed as its own argument and is never empty`() = runBlocking {
        val runner = helper(recognitionJson = """{"ok":true,"text":"x"}""")

        backend(runner).recognize(imageData(), LanguageCode.JAPANESE).unwrap()

        val command = runner.commands.single { !it.contains(COMMAND_CAPABILITIES) }
        assertEquals(LanguageCode.JAPANESE.tag, command.argumentValue("-Language"))
        assertEquals("powershell.exe", command.first())
        assertTrue(command.contains("-File"))
    }

    @Test
    fun `an image larger than the engine's reported limit is scaled below it`() = runBlocking {
        // The engine's limit, OcrEngine.MaxImageDimension, is faked as 64 here.
        var observed: Pair<Int, Int>? = null
        val runner = helper(
            capabilities = """{"ok":true,"languages":["en-US"],"maxImageDimension":64}""",
            recognitionJson = """{"ok":true,"text":"x"}""",
            onImageBytes = { observed = encodedImageSize(it) },
        )

        backend(runner).recognize(imageData(width = 400, height = 200), LanguageCode.ENGLISH).unwrap()

        val (width, height) = observed ?: error("the helper never received an image")
        assertTrue(width <= 64 && height <= 64, "expected the image to be reduced below 64, but was ${width}x$height")
        assertEquals(64, width)
        assertEquals(32, height)
    }

    @Test
    fun `the engine capabilities are read once and cached`() = runBlocking {
        val runner = helper(recognitionJson = """{"ok":true,"text":"x"}""")
        val backend = backend(runner)

        backend.recognize(imageData(), LanguageCode.ENGLISH).unwrap()
        backend.recognize(imageData(), LanguageCode.ENGLISH).unwrap()

        assertEquals(1, runner.commands.count { it.contains(COMMAND_CAPABILITIES) })
    }

    @Test
    fun `supportedLanguages comes from the engine and never claims language detection`() = runBlocking {
        val backend = backend(helper(capabilities = """{"ok":true,"languages":["en-US","ja-JP"],"maxImageDimension":10000}"""))

        val supported = backend.supportedLanguages().unwrap()

        assertEquals(setOf(LanguageCode("en-US"), LanguageCode("ja-JP")), supported.languages)
        assertFalse(supported.detectsLanguage)
    }

    @Test
    fun `a single-element language list rendered as a scalar is accepted`() = runBlocking {
        val backend = backend(helper(capabilities = """{"ok":true,"languages":"en-US","maxImageDimension":10000}"""))

        assertEquals(setOf(LanguageCode("en-US")), backend.supportedLanguages().unwrap().languages)
    }

    @Test
    fun `missing capabilities are reported rather than assumed`() = runBlocking {
        val backend = backend(helper(capabilities = """{"ok":true,"languages":["en-US"]}"""))

        assertTrue(backend.supportedLanguages().unwrapError() is ServiceError.InvalidResponseError)
    }

    @Test
    fun `a missing powershell is a configuration error`() = runBlocking {
        val backend = backend(helper(recognitionJson = """{"ok":true,"text":"x"}"""), powershell = null)

        assertTrue(backend.validate().unwrapError() is ServiceError.ConfigurationError)
        assertTrue(backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError() is ServiceError.ConfigurationError)
    }

    @Test
    fun `a missing helper script is a configuration error`() = runBlocking {
        val backend = WindowsOcrBackend(helper(), scriptFile = null, powershell = "powershell.exe")

        assertTrue(backend.validate().unwrapError() is ServiceError.ConfigurationError)
    }

    @Test
    fun `validate succeeds when the tooling is present`() = runBlocking {
        assertEquals(Unit, backend(helper()).validate().unwrap())
    }

    @Test
    fun `a program that cannot be launched becomes an Err, not a thrown exception`() = runBlocking {
        val absent = File(
            Files.createTempDirectory("system-ocr-absent").toFile(),
            "no-such-powershell",
        ).absolutePath
        val backend = WindowsOcrBackend(RealProcessRunner(), script, absent)

        val error = backend.recognize(imageData(), LanguageCode.ENGLISH).unwrapError()

        assertTrue(error is ServiceError.ConfigurationError, "was ${error::class.simpleName}")
    }

    @Test
    fun `recognized lines are rebuilt in logical order and kept top to bottom`() = runBlocking {
        val json = """
            {"ok":true,"lines":[
              {"text":"الترمنال يعشق من الا قيمته يعرف لن رهييييب","words":[
                {"t":"الترمنال","x":0},{"t":"يعشق","x":10},{"t":"من","x":20},{"t":"الا","x":30},
                {"t":"قيمته","x":40},{"t":"يعرف","x":50},{"t":"لن","x":60},{"t":"رهييييب","x":70}]},
              {"text":"QTranslate OCR Test","words":[
                {"t":"QTranslate","x":0},{"t":"OCR","x":10},{"t":"Test","x":20}]}
            ]}
        """.trimIndent()
        val backend = backend(helper(recognitionJson = json))

        val text = backend.recognize(imageData(), LanguageCode("ar")).unwrap()

        assertEquals(
            "رهييييب لن يعرف قيمته الا من يعشق الترمنال\nQTranslate OCR Test",
            text,
        )
    }

    @Test
    fun `a helper reporting only flat text is still accepted`() = runBlocking {
        val backend = backend(helper(recognitionJson = """{"ok":true,"text":"Hello\nWorld"}"""))

        assertEquals("Hello\nWorld", backend.recognize(imageData(), LanguageCode.ENGLISH).unwrap())
    }

    private companion object {
        const val COMMAND_CAPABILITIES = "capabilities"
        const val CAPABILITIES = """{"ok":true,"languages":["en-US"],"maxImageDimension":10000}"""
    }
}

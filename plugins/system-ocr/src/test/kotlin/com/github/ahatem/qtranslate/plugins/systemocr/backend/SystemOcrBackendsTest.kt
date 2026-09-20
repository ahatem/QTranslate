package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.FakeProcessRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import com.github.ahatem.qtranslate.plugins.systemocr.unwrap
import com.github.ahatem.qtranslate.plugins.systemocr.unwrapError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemOcrBackendsTest {

    private val runner = FakeProcessRunner { ProcessOutcome(0, "", "", false) }
    private val locator = ExecutableLocator { name -> "/usr/bin/$name" }

    private fun create(osName: String): com.github.michaelbull.result.Result<SystemOcrBackend, ServiceError> {
        val directory = Files.createTempDirectory("system-ocr-backends").toFile()
        return SystemOcrBackends.create(osName, directory, runner, locator)
    }

    @Test
    fun `windows selects the Windows Media OCR backend`() {
        assertTrue(create("Windows 11").unwrap() is WindowsOcrBackend)
    }

    @Test
    fun `mac os selects the Vision backend`() {
        assertTrue(create("Mac OS X").unwrap() is MacVisionOcrBackend)
    }

    @Test
    fun `darwin also selects the Vision backend`() {
        assertTrue(create("Darwin").unwrap() is MacVisionOcrBackend)
    }

    @Test
    fun `linux selects the Tesseract backend`() {
        assertTrue(create("Linux").unwrap() is LinuxTesseractBackend)
    }

    @Test
    fun `an unsupported platform fails cleanly`() {
        val error = create("FreeBSD").unwrapError()
        assertTrue(error is ServiceError.ConfigurationError)
    }

    @Test
    fun `the windows backend stages its script out of the jar`() = runBlocking {
        val backend = create("Windows 11").unwrap()

        // The script resource ships in the JAR, so it is present and runnable straight away.
        assertEquals(Unit, backend.validate().unwrap())
    }
}

package com.github.ahatem.qtranslate.plugins.systemservices.backend

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.FakeProcessRunner
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import com.github.ahatem.qtranslate.plugins.systemservices.unwrap
import com.github.ahatem.qtranslate.plugins.systemservices.unwrapError
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
    fun `each platform selects its OCR backend`() {
        val expected = listOf(
            "Windows 11" to WindowsOcrBackend::class,
            "Mac OS X" to MacVisionOcrBackend::class,
            "Darwin" to MacVisionOcrBackend::class,
            "Linux" to LinuxTesseractBackend::class,
        )
        for ((osName, backend) in expected) {
            assertEquals(backend, create(osName).unwrap()::class, osName)
        }
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

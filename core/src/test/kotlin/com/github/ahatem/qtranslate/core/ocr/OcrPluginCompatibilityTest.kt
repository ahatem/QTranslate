package com.github.ahatem.qtranslate.core.ocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.main.mvi.Silent353Factory
import com.github.ahatem.qtranslate.core.plugin.PluginLoader
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loads a real plugin JAR whose provider implements only the original text OCR interface and
 * declares the older API 2.0.0 manifest contract. The host must still construct it, accept its
 * declared compatibility, and run it through the same [OcrExtractionUseCase] as providers that
 * opt into the additive [com.github.ahatem.qtranslate.api.ocr.StructuredOCR] capability.
 *
 * This is a source-fixture compiled with the test suite rather than a checked-in binary built
 * against the 2.0 API. It covers the text-only plugin shape and host acceptance path; the API
 * surface itself is additionally guarded by `:api:checkKotlinAbi`.
 */
class OcrPluginCompatibilityTest {

    @Test
    fun `a text-only plugin declaring the older API still loads and recognizes`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-legacy-ocr").toFile()
        try {
            val jar = directory.resolve("legacy-ocr.jar")
            JarOutputStream(jar.outputStream()).use { out ->
                out.putNextEntry(JarEntry("META-INF/services/${Plugin::class.java.name}"))
                out.write(TextOnlyOcrPlugin::class.java.name.toByteArray())
                out.closeEntry()
                out.putNextEntry(JarEntry("plugin.json"))
                out.write(
                    """{"id":"legacy-ocr","name":"Legacy OCR","version":"1.0.0","author":"test","description":"text only","minApiVersion":"2.0.0"}"""
                        .toByteArray()
                )
                out.closeEntry()
                out.copyClass(TextOnlyOcrPlugin::class.java)
                out.copyClass(TextOnlyOcrService::class.java)
            }

            val loader = PluginLoader(Silent353Factory.getLogger("compat"), directory.resolve("cache"))
            val assessment = loader.assessFile(jar)
            assertTrue(assessment != null, loader.loadFailures.map { it.message }.toString())
            val loaded = loader.loadApproved(assessment)
            assertTrue(loaded != null, loader.loadFailures.map { it.message }.toString())

            val service = loaded.plugin.getServices().filterIsInstance<OCR>().single()
            val manager = ActiveServiceManager(
                activeServices = MutableStateFlow(mapOf("fixture.legacy-ocr" to service)),
                configuration = MutableStateFlow(Configuration.DEFAULT),
            )
            val outcome = OcrExtractionUseCase(manager, Silent353Factory).extract(
                image = ImageData(byteArrayOf(1, 2, 3), "png", 8, 8),
                language = LanguageCode.AUTO,
            )

            val success = outcome as OcrExtractionOutcome.Success
            assertEquals("legacy text", success.result.text)
            assertEquals("fixture.legacy-ocr", success.result.serviceId)

            (loaded.classLoader as java.net.URLClassLoader).close()
            assessment.discard()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun JarOutputStream.copyClass(type: Class<*>) {
        val path = type.name.replace('.', '/') + ".class"
        putNextEntry(JarEntry(path))
        requireNotNull(type.classLoader.getResourceAsStream(path)) { "Missing fixture class $path" }
            .use { it.copyTo(this) }
        closeEntry()
    }
}

class TextOnlyOcrPlugin : Plugin<PluginSettings.None> {
    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> = Ok(Unit)
    override fun getServices(): List<Service> = listOf(TextOnlyOcrService())
    override fun getSettings(): PluginSettings.None = PluginSettings.None
}

class TextOnlyOcrService : OCR {
    override val key = "legacy-ocr"
    override val name = "Legacy OCR"
    override val version = "1.0.0"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
        Ok(OCRResponse("legacy text"))
}

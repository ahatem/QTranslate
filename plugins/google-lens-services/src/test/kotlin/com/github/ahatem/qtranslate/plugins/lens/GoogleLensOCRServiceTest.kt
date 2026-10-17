package com.github.ahatem.qtranslate.plugins.lens

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrThrow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoogleLensOCRServiceTest {

    private val sampleImage = ImageData(
        bytes = byteArrayOf(0x01, 0x02, 0x03),
        format = "png",
        width = 100,
        height = 100
    )

    private fun buildResponseFixture(lines: List<String>, language: String? = null): ByteArray {
        val textLayoutStream = ByteArrayOutputStream()
        for (line in lines) {
            val words = line.split(" ")
            val lineStream = ByteArrayOutputStream()
            for ((idx, word) in words.withIndex()) {
                val sep = if (idx == words.lastIndex) "" else " "
                val wordStream = ByteArrayOutputStream()
                writeLengthDelimited(wordStream, 2, word.toByteArray(StandardCharsets.UTF_8))
                writeLengthDelimited(wordStream, 3, sep.toByteArray(StandardCharsets.UTF_8))
                writeLengthDelimited(lineStream, 1, wordStream.toByteArray())
            }
            val paragraphStream = ByteArrayOutputStream()
            writeLengthDelimited(paragraphStream, 2, lineStream.toByteArray())
            writeLengthDelimited(textLayoutStream, 1, paragraphStream.toByteArray())
        }

        val textStream = ByteArrayOutputStream()
        writeLengthDelimited(textStream, 1, textLayoutStream.toByteArray())
        if (language != null) {
            writeLengthDelimited(textStream, 2, language.toByteArray(StandardCharsets.UTF_8))
        }

        val objectsResponseStream = ByteArrayOutputStream()
        writeLengthDelimited(objectsResponseStream, 3, textStream.toByteArray())

        val rootStream = ByteArrayOutputStream()
        writeLengthDelimited(rootStream, 2, objectsResponseStream.toByteArray())

        return rootStream.toByteArray()
    }

    private fun writeLengthDelimited(stream: ByteArrayOutputStream, fieldNumber: Int, bytes: ByteArray) {
        val tag = (fieldNumber shl 3) or 2
        writeVarint(stream, tag)
        writeVarint(stream, bytes.size)
        stream.write(bytes)
    }

    private fun writeVarint(stream: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            stream.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        stream.write(v and 0x7F)
    }

    private class FakeLensHttpClient(
        var response: Result<ByteArray, ServiceError>
    ) : TextHttpClient() {
        var lastPostedUrl: String? = null
        var lastPostedHeaders: Map<String, String>? = null
        var lastPostedBody: ByteArray? = null

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> = error("Unexpected get: $url")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> = error("Unexpected post: $url")

        override suspend fun postBytes(
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            queryParams: Map<String, Any?>
        ): Result<ByteArray, ServiceError> {
            lastPostedUrl = url
            lastPostedHeaders = headers
            lastPostedBody = body
            return response
        }
    }

    @Test
    fun `extractText returns recognized text and detected language on valid response`() = runBlocking {
        val fixture = buildResponseFixture(listOf("Hello World", "Line two"), language = "en")
        val fakeHttp = FakeLensHttpClient(Ok(fixture))
        val context = FakePluginContext(httpClient = fakeHttp)
        val service = GoogleLensOCRService(context, fakeHttp)

        val request = OCRRequest(sampleImage, LanguageCode.AUTO)
        val response = service.extractText(request).getOrThrow { AssertionError(it.message) }

        assertEquals("Hello World\nLine two", response.text)
        assertEquals(LanguageCode.ENGLISH, response.detectedLanguage)

        assertEquals("https://lensfrontend-pa.googleapis.com/v1/crupload", fakeHttp.lastPostedUrl)
        assertEquals("application/x-protobuf", fakeHttp.lastPostedHeaders?.get("Content-Type"))
        assertTrue(fakeHttp.lastPostedBody?.isNotEmpty() == true)
    }

    @Test
    fun `extractText returns distinct InvalidResponseError when no text is detected in image`() = runBlocking {
        // Return an empty protobuf payload (image contains no recognizable text)
        val emptyFixture = ByteArray(0)
        val fakeHttp = FakeLensHttpClient(Ok(emptyFixture))
        val context = FakePluginContext(httpClient = fakeHttp)
        val service = GoogleLensOCRService(context, fakeHttp)

        val request = OCRRequest(sampleImage, LanguageCode.AUTO)
        val error = service.extractText(request).getError()

        assertNotNull(error, "Empty text must produce an error result instead of an empty success")
        assertIs<ServiceError.InvalidResponseError>(error)
        assertEquals("No text detected in the image", error.message)
    }

    @Test
    fun `extractText propagates network errors cleanly without leaking raw exceptions`() = runBlocking {
        val fakeHttp = FakeLensHttpClient(Err(ServiceError.NetworkError("Connection refused by host")))
        val context = FakePluginContext(httpClient = fakeHttp)
        val service = GoogleLensOCRService(context, fakeHttp)

        val request = OCRRequest(sampleImage, LanguageCode.SPANISH)
        val error = service.extractText(request).getError()

        assertNotNull(error)
        assertIs<ServiceError.NetworkError>(error)
        assertEquals("Connection refused by host", error.message)
    }

    @Test
    fun `extractText propagates rate limit errors from transport`() = runBlocking {
        val fakeHttp = FakeLensHttpClient(Err(ServiceError.RateLimitError("Rate limit exceeded", retryAfterSeconds = 30)))
        val context = FakePluginContext(httpClient = fakeHttp)
        val service = GoogleLensOCRService(context, fakeHttp)

        val request = OCRRequest(sampleImage, LanguageCode.AUTO)
        val error = service.extractText(request).getError()

        assertNotNull(error)
        assertIs<ServiceError.RateLimitError>(error)
        assertEquals(30, error.retryAfterSeconds)
    }

    @Test
    fun `fetchSupportedLanguages returns success with dynamic languages`() = runBlocking {
        val fakeHttp = FakeLensHttpClient(Ok(ByteArray(0)))
        val context = FakePluginContext(httpClient = fakeHttp)
        val service = GoogleLensOCRService(context, fakeHttp)

        val supported = service.fetchSupportedLanguages().getOrThrow { AssertionError(it.message) }
        assertTrue(LanguageCode.ENGLISH in supported)
        assertTrue(LanguageCode.SPANISH in supported)
    }
}

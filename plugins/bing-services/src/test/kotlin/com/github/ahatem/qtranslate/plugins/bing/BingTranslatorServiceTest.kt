package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the shapes the translate endpoint returns besides a translation array: a provider error
 * object, an interstitial page, and JSON that is not a translation response.
 */
class BingTranslatorServiceTest {

    private val request = TranslationRequest(
        text = "Hello, how are you?",
        sourceLanguage = LanguageCode.AUTO,
        targetLanguage = LanguageCode.SPANISH
    )

    private fun createService(client: BingTestHttpClient) = BingTranslatorService(
        FakePluginContext(),
        client,
        BingAuthManager(FakePluginContext(), client),
        BingLanguageMapper,
        ApiConfig()
    )

    private fun translateWith(body: String) = runBlocking {
        createService(BingTestHttpClient(Ok(body))).translate(request)
    }

    /** The error a body produced, failing the test if it unexpectedly succeeded. */
    private fun errorFrom(body: String): ServiceError {
        val error = translateWith(body).getError()
        assertNotNull(error, "body: $body")
        return error
    }

    @Test
    fun `the posted target language uses a code bing accepts`() = runBlocking {
        val codes = mapOf(
            LanguageCode.PORTUGUESE_BRAZIL to "pt",
            LanguageCode.PORTUGUESE_PORTUGAL to "pt-PT",
            LanguageCode.SERBIAN to "sr-Cyrl"
        )

        codes.forEach { (language, expected) ->
            val client = BingTestHttpClient(Ok(SUCCESS_ARRAY))
            createService(client).translate(request.copy(targetLanguage = language))

            assertEquals(expected, client.lastTranslateForm["to"], "target: $language")
        }
    }

    @Test
    fun `a translation array is decoded as before`() = runBlocking {
        translateWith(SUCCESS_ARRAY).fold(
            success = {
                assertEquals("Hola, ¿cómo estás?", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `an error object carrying a provider message reports both`() {
        val error = errorFrom(
            """{"statusCode":"400","errorMessage":"Invalid request. Invalid from language."}"""
        )

        assertIs<ServiceError.InvalidResponseError>(error)
        assertContains(error.message, "400")
        assertContains(error.message, "Invalid request. Invalid from language.")
        assertContains(error.message, REJECTED)
    }

    @Test
    fun `an error object with a blank message still names the status`() {
        val error = errorFrom("""{"statusCode":"400","errorMessage":""}""")

        assertIs<ServiceError.InvalidResponseError>(error)
        assertContains(error.message, "400")
        assertContains(error.message, REJECTED)
        assertFalse(error.message.contains("errorMessage"), "message named a JSON field: ${error.message}")
    }

    @Test
    fun `an error object with no message at all still names the status`() {
        val error = errorFrom("""{"statusCode":"400"}""")

        assertIs<ServiceError.InvalidResponseError>(error)
        assertContains(error.message, "400")
        assertContains(error.message, REJECTED)
    }

    @Test
    fun `a numeric status code is read rather than failing to decode`() {
        val error = errorFrom("""{"statusCode":400,"errorMessage":""}""")

        assertIs<ServiceError.InvalidResponseError>(error)
        assertContains(error.message, "400")
        assertContains(error.message, REJECTED)
    }

    @Test
    fun `an error object with no status code is still reported cleanly`() {
        val error = errorFrom("""{"errorMessage":"Something went wrong"}""")

        assertIs<ServiceError.InvalidResponseError>(error)
        assertContains(error.message, "Something went wrong")
        assertContains(error.message, REJECTED)
    }

    @Test
    fun `a page instead of json is temporary rather than a permanent failure`() {
        val error = errorFrom(HTML_RESPONSE)

        assertIs<ServiceError.ServiceUnavailableError>(error)
        assertTrue(error.isRetryable)
        assertFalse(error.message.contains("<html", ignoreCase = true), "message carried markup: ${error.message}")
        assertNoParserInternals(error.message)
    }

    @Test
    fun `an empty body is reported as unexpected rather than crashing`() {
        val error = errorFrom("")

        assertIs<ServiceError.ServiceUnavailableError>(error)
        assertNoParserInternals(error.message)
    }

    @Test
    fun `malformed json is reported without its parser text`() {
        val error = errorFrom("""[{"translations": [ }""")

        assertIs<ServiceError.InvalidResponseError>(error)
        assertFalse(error.isRetryable)
        assertNoParserInternals(error.message)
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `an object with no bing error field is an unexpected shape, not a rejection`() {
        listOf("{}", """{"foo":"bar"}""", """{"translations":[]}""").forEach { body ->
            val error = errorFrom(body)

            assertIs<ServiceError.InvalidResponseError>(error, "body: $body")
            assertEquals(
                "Bing Translate returned an unexpected response. Please try again.",
                error.message,
                "body: $body"
            )
            assertFalse(error.message.contains(REJECTED), "body read as a rejection: $body")
            assertNoParserInternals(error.message)
        }
    }

    @Test
    fun `a primitive root is an unexpected shape`() {
        listOf(""""hello"""", "123", "true", "null").forEach { body ->
            val error = errorFrom(body)

            assertIs<ServiceError.InvalidResponseError>(error, "body: $body")
            assertEquals(
                "Bing Translate returned an unexpected response. Please try again.",
                error.message,
                "body: $body"
            )
            assertFalse(error.message.contains(REJECTED), "body read as a rejection: $body")
            assertNoParserInternals(error.message)
        }
    }

    @Test
    fun `an empty array keeps the existing invalid-response wording`() {
        val error = errorFrom("[]")

        assertIs<ServiceError.InvalidResponseError>(error)
        assertEquals("Empty or invalid response from Bing", error.message)
    }

    @Test
    fun `an array entry without a translation keeps the existing wording`() {
        val error = errorFrom("""[{"detectedLanguage":{"language":"en"}}]""")

        assertIs<ServiceError.InvalidResponseError>(error)
        assertEquals("Empty or invalid response from Bing", error.message)
    }

    @Test
    fun `no provider failure reaches the user as parser internals`() {
        val bodies = listOf(
            """{"statusCode":"400","errorMessage":""}""",
            """{"statusCode":400}""",
            """{"errorMessage":"Something went wrong"}""",
            HTML_RESPONSE,
            "",
            "[{\"translations\": [",
            "{}",
            """{"foo":"bar"}""",
            """"hello"""",
            "123"
        )

        bodies.forEach { body ->
            val message = errorFrom(body).message
            assertNoParserInternals(message)
            assertTrue(message.startsWith("Bing Translate"), "message was not user-facing: $message")
        }
    }

    /**
     * The defect was a serialization message shown verbatim, so the wording is asserted rather than
     * the error type. Patterns are word-bounded so "unexpected response" does not read as a leaked
     * "expected".
     */
    private fun assertNoParserInternals(message: String) {
        listOf(
            "expected start",
            "unexpected json token",
            "\\boffset\\b",
            "path: \\$",
            "SerializationException",
            "JsonDecodingException",
            "\\bexpected\\b",
            "but had"
        ).forEach { leak ->
            val found = Regex(leak, RegexOption.IGNORE_CASE).containsMatchIn(message)
            assertFalse(found, "message leaked '$leak': $message")
        }
    }

    private companion object {
        const val REJECTED = "rejected the request"

        val SUCCESS_ARRAY = """
            [
              {
                "detectedLanguage": {"language":"en","score":1.0},
                "translations": [
                  {"text":"Hola","to":"es"},
                  {"text":", ","to":"es"},
                  {"text":"¿cómo estás?","to":"es"}
                ]
              }
            ]
        """.trimIndent()

        val HTML_RESPONSE = """
            <!DOCTYPE html><html><head><title>Drowned</title></head><body>Drowned</body></html>
        """.trimIndent()
    }
}

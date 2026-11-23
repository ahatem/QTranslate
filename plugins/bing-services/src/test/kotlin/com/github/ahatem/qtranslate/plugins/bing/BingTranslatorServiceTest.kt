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
    fun `an error object with an auth status is an authentication failure`() {
        // String and numeric status codes both count.
        for (body in listOf(
            """{"statusCode":"401","errorMessage":""}""",
            """{"statusCode":"403","errorMessage":""}""",
            """{"statusCode":401,"errorMessage":""}"""
        )) {
            val error = errorFrom(body)

            assertIs<ServiceError.AuthenticationError>(error, "body: $body")
            assertContains(error.message, Regex("\\d{3}").find(body)!!.value)
            assertContains(error.message, REJECTED)
            assertUserFacing(error.message)
        }
    }

    @Test
    fun `an error object that is not an auth failure reports the status and any provider message`() {
        // body to substring the message must carry besides the status.
        val cases = listOf(
            """{"statusCode":"400","errorMessage":"Invalid request. Invalid from language."}""" to "Invalid request. Invalid from language.",
            """{"statusCode":"400","errorMessage":""}""" to "400",
            """{"statusCode":"400"}""" to "400",
            """{"statusCode":400,"errorMessage":""}""" to "400",
            """{"errorMessage":"Something went wrong"}""" to "Something went wrong",
        )
        for ((body, expected) in cases) {
            val error = errorFrom(body)

            assertIs<ServiceError.InvalidResponseError>(error, "body: $body")
            assertContains(error.message, expected)
            assertContains(error.message, REJECTED)
            assertFalse(error.message.contains("errorMessage"), "message named a JSON field: ${error.message}")
            assertUserFacing(error.message)
        }
    }

    @Test
    fun `a body that is not json is reported without markup or parser text`() {
        val page = errorFrom(HTML_RESPONSE)
        assertIs<ServiceError.ServiceUnavailableError>(page)
        assertTrue(page.isRetryable, "a page instead of json is temporary")
        assertFalse(page.message.contains("<html", ignoreCase = true), "message carried markup: ${page.message}")
        assertUserFacing(page.message)

        val empty = errorFrom("")
        assertIs<ServiceError.ServiceUnavailableError>(empty)
        assertUserFacing(empty.message)

        val malformed = errorFrom("""[{"translations": [ }""")
        assertIs<ServiceError.InvalidResponseError>(malformed)
        assertFalse(malformed.isRetryable)
        assertUserFacing(malformed.message)
    }

    @Test
    fun `json with no bing error field is an unexpected shape, not a rejection`() {
        val bodies = listOf("{}", """{"foo":"bar"}""", """{"translations":[]}""", """"hello"""", "123", "true", "null")
        for (body in bodies) {
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
    fun `an empty array or an entry without a translation keeps the invalid-response wording`() {
        for (body in listOf("[]", """[{"detectedLanguage":{"language":"en"}}]""")) {
            val error = errorFrom(body)

            assertIs<ServiceError.InvalidResponseError>(error, "body: $body")
            assertEquals("Empty or invalid response from Bing", error.message, "body: $body")
        }
    }

    private fun assertUserFacing(message: String) {
        assertNoParserInternals(message)
        assertTrue(message.startsWith("Bing Translate"), "message was not user-facing: $message")
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

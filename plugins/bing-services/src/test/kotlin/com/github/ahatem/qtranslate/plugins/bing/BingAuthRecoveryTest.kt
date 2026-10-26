package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue 366: a token the endpoint rejected stayed cached, so every later translation failed with
 * "Authentication failed for https://www.bing.com/ttranslatev3" until the app was restarted.
 * The recovery contract under test: an authentication rejection drops the token, refreshes it
 * once, and retries the translation once — never more.
 */
class BingAuthRecoveryTest {

    private val request = TranslationRequest(
        text = "Hello, how are you?",
        sourceLanguage = LanguageCode.AUTO,
        targetLanguage = LanguageCode.SPANISH
    )

    private fun authRejected(status: String) =
        Ok("""{"statusCode":"$status","errorMessage":""}""")

    private fun service(client: BingTestHttpClient) = BingTranslatorService(
        FakePluginContext(),
        client,
        BingAuthManager(FakePluginContext(), client),
        BingLanguageMapper,
        ApiConfig()
    )

    private fun translate(client: BingTestHttpClient) = runBlocking {
        service(client).translate(request)
    }

    @Test
    fun `an authentication rejection refreshes the token and retries once`() {
        val client = BingTestHttpClient(authRejected("401")).apply {
            authPage = Ok(BingTestHttpClient.authPage(token = BingTestHttpClient.TOKEN))
        }
        client.translateQueue += BingTestHttpClient.successArray()

        val result = translate(client)

        result.fold(
            success = { assertEquals("Hola, ¿cómo estás?", it.translatedText) },
            failure = { fail("recovery should have succeeded: ${it.message}") }
        )
        assertEquals(2, client.translateCalls, "exactly one retry after the rejection")
        assertEquals(2, client.authFetches, "one fetch before the rejection, one after")
        assertEquals(BingTestHttpClient.TOKEN, client.lastTranslateForm["token"])
    }

    @Test
    fun `a retried request carries the refreshed token, not the rejected one`() {
        val client = BingTestHttpClient(authRejected("401")).apply {
            authPage = Ok(BingTestHttpClient.authPage(token = FIRST_TOKEN))
            authPageAfterFirst = Ok(BingTestHttpClient.authPage(token = SECOND_TOKEN))
        }
        client.translateQueue += BingTestHttpClient.successArray()

        translate(client)

        assertEquals(SECOND_TOKEN, client.lastTranslateForm["token"])
    }

    @Test
    fun `a second rejection stops the recovery after one retry`() {
        val client = BingTestHttpClient(authRejected("401"))
        client.translateQueue += authRejected("401")

        val error = translate(client).getError()

        assertIs<ServiceError.AuthenticationError>(error)
        assertEquals(2, client.translateCalls, "retry must not loop")
        assertEquals(2, client.authFetches, "refresh must not loop")
    }

    @Test
    fun `a 403 status is treated as an authentication rejection too`() {
        val client = BingTestHttpClient(authRejected("403"))
        client.translateQueue += BingTestHttpClient.successArray()

        val result = translate(client)

        result.fold(
            success = { },
            failure = { error -> fail("a 403 body rejection should trigger the same recovery: ${error.message}") }
        )
        assertEquals(2, client.translateCalls)
        assertEquals(2, client.authFetches)
    }

    @Test
    fun `a failed refresh is surfaced instead of a doomed retry`() {
        val client = BingTestHttpClient(authRejected("401")).apply {
            // The first fetch succeeds, so the initial token is cached and the request runs;
            // the rejection then asks for a refresh, and the page fails this time.
            authPageAfterFirst = Err(ServiceError.NetworkError("GET request failed", null))
        }

        val error = translate(client).getError()

        assertIs<ServiceError.NetworkError>(error)
        assertEquals(1, client.translateCalls, "no retry without a fresh token")
        assertEquals(2, client.authFetches, "the refresh was still attempted")
    }

    @Test
    fun `a non-authentication rejection is neither retried nor invalidated`() {
        val client = BingTestHttpClient(
            Ok("""{"statusCode":"400","errorMessage":"Invalid request. Invalid from language."}""")
        )

        val error = translate(client).getError()

        assertIs<ServiceError.InvalidResponseError>(error)
        assertEquals(1, client.translateCalls)
        assertEquals(1, client.authFetches, "a bad request says nothing about the token")
    }

    @Test
    fun `a network failure is neither retried nor invalidated`() {
        val client = BingTestHttpClient(Err(ServiceError.NetworkError("POST request failed", null)))

        val error = translate(client).getError()

        assertIs<ServiceError.NetworkError>(error)
        assertEquals(1, client.translateCalls)
        assertEquals(1, client.authFetches)
    }

    @Test
    fun `a valid cached token keeps working without refetching`() {
        val client = BingTestHttpClient(BingTestHttpClient.successArray())
        val translator = service(client)

        runBlocking {
            translator.translate(request)
            translator.translate(request)
        }

        assertEquals(2, client.translateCalls)
        assertEquals(1, client.authFetches, "the cached token serves the second translation")
    }

    @Test
    fun `a token refreshed by a rejection serves the next translation`() {
        val client = BingTestHttpClient(authRejected("401")).apply {
            authPage = Ok(BingTestHttpClient.authPage(token = FIRST_TOKEN))
            authPageAfterFirst = Ok(BingTestHttpClient.authPage(token = SECOND_TOKEN))
        }
        client.translateQueue += BingTestHttpClient.successArray()
        val translator = service(client)

        runBlocking {
            translator.translate(request)
            translator.translate(request)
        }

        assertEquals(3, client.translateCalls, "one retry, then the second translation")
        assertEquals(2, client.authFetches, "the refreshed token is cached and reused")
        assertEquals(SECOND_TOKEN, client.lastTranslateForm["token"])
    }

    @Test
    fun `a rejection message never carries the token, key, muid or user text`() {
        val client = BingTestHttpClient(authRejected("401"))
        client.translateQueue += authRejected("401")

        val message = translate(client).getError()?.message.orEmpty()

        assertTrue(message.isNotBlank())
        listOf(
            BingTestHttpClient.TOKEN,
            BingTestHttpClient.KEY,
            BingTestHttpClient.MUID,
            BingTestHttpClient.IG,
            request.text
        ).forEach { secret ->
            assertTrue(!message.contains(secret), "message leaked '$secret': $message")
        }
    }

    private companion object {
        const val FIRST_TOKEN = "first-token-value"
        const val SECOND_TOKEN = "second-token-value"
    }
}

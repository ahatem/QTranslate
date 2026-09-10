package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class GoogleSpellCheckerServiceTest {

    private fun createService(
        client: GoogleTestHttpClient,
        health: GoogleEndpointHealth = GoogleEndpointHealth({ 0L }, { 0L })
    ) = GoogleSpellCheckerService(
        FakePluginContext(),
        client,
        GoogleLanguageMapper,
        ApiConfig(),
        health
    )

    @Test
    fun `open primary circuit fails spell check without a request`() = runBlocking {
        val clock = AtomicLong(0)
        val health = GoogleEndpointHealth({ clock.get() }, { 0L })
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index < 2) Err(ServiceError.TimeoutError("timed out")) else Ok(SPELL_JSON)
            },
            fallbackHandler = { Ok("""["Bonjour","en"]""") }
        )
        val translator = GoogleTranslatorService(
            FakePluginContext(),
            GoogleSettings(),
            client,
            GoogleLanguageMapper,
            ApiConfig(),
            health
        )
        val spellChecker = createService(client, health)

        val translationRequest = TranslationRequest("Hello", LanguageCode.AUTO, LanguageCode.FRENCH)
        translator.translate(translationRequest)
        translator.translate(translationRequest)
        assertEquals(2, client.primaryCalls)

        val result = spellChecker.check(SpellCheckRequest("Helo world."))

        assertTrue(result.isErr)
        assertEquals(2, client.primaryCalls)
    }

    @Test
    fun `spell check request concurrency is bounded`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = {
                release.await()
                Ok(SPELL_JSON)
            }
        )
        val spellChecker = createService(client)

        val job = async { spellChecker.check(requestWithTenSentences()) }

        var attempts = 0
        while (client.maxInFlight < 4 && attempts < 10_000) {
            yield()
            attempts++
        }

        assertEquals(4, client.maxInFlight)
        release.complete(Unit)
        job.await().fold(
            success = {},
            failure = { fail(it.message) }
        )
        assertEquals(4, client.maxInFlight)
    }

    @Test
    fun `failed spell request is not cached`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index == 0) Err(ServiceError.TimeoutError("timed out")) else Ok(SPELL_JSON)
            }
        )
        val spellChecker = createService(client)

        assertTrue(spellChecker.check(SpellCheckRequest("Helo world.")).isErr)
        assertTrue(spellChecker.check(SpellCheckRequest("Helo world.")).isOk)

        assertEquals(2, client.primaryCalls)
    }

    @Test
    fun `unrecognised spell payload is not treated as no corrections and is not cached`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok("""{"error":{"code":429}}""") }
        )
        val spellChecker = createService(client)

        assertTrue(spellChecker.check(SpellCheckRequest("Helo world.")).isErr)
        assertTrue(spellChecker.check(SpellCheckRequest("Helo world.")).isErr)

        assertEquals(2, client.primaryCalls)
    }

    @Test
    fun `genuine no-correction response is cached`() = runBlocking {
        val client = GoogleTestHttpClient(primaryHandler = { Ok(SPELL_JSON) })
        val spellChecker = createService(client)

        assertTrue(spellChecker.check(SpellCheckRequest("Helo world.")).isOk)
        assertTrue(spellChecker.check(SpellCheckRequest("Helo world.")).isOk)

        assertEquals(1, client.primaryCalls)
    }

    @Test
    fun `spell check queued behind the semaphore does not hit the primary after the circuit opens`() = runBlocking {
        val entered = AtomicInteger(0)
        val fourInFlight = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = {
                if (entered.incrementAndGet() == 4) fourInFlight.complete(Unit)
                gate.await()
                Err(ServiceError.RateLimitError("rate limited"))
            }
        )
        val spellChecker = createService(client)

        val job = async { spellChecker.check(requestWithTenSentences()) }
        fourInFlight.await()
        gate.complete(Unit)

        // The first failure opens the circuit, so the six queued sentences issue no more requests.
        assertTrue(job.await().isErr)
        assertEquals(4, client.primaryCalls)
    }

    private fun requestWithTenSentences() =
        SpellCheckRequest(text = (1..10).joinToString(" ") { "Sentence$it." })

    private companion object {
        const val SPELL_JSON = """{"sentences":[{"trans":"translated","orig":"original"}],"src":"en"}"""
    }
}

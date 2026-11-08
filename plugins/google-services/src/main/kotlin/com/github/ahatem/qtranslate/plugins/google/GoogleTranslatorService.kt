package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.PluginJson
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.common.getOnce
import com.github.ahatem.qtranslate.plugins.common.sendJson
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.OfficialTranslateResponse
import com.github.ahatem.qtranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.toResultOr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class GoogleTranslatorService(
    private val pluginContext: PluginContext,
    private val settings: GoogleSettings,
    private val httpClient: HttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig,
    private val endpointHealth: GoogleEndpointHealth,
    /**
     * How long the primary endpoint is given on its own before the fallback is asked as well.
     *
     * Not a network budget: the primary's request keeps its own transport timeout and its answer
     * is still used if the fallback cannot be reached. It is only the point after which serving the
     * user from the secondary host is worth a second request, which is what turns the primary's
     * throttle into a short delay instead of the whole timeout.
     */
    private val primaryHedgeMillis: Long = PRIMARY_HEDGE_MILLIS,
    /**
     * Time source for [hedgeBudget]'s quiet period, injectable so the recovery path is
     * deterministic in tests. The shared [GoogleEndpointHealth] keeps its own clock.
     */
    clock: () -> Long = { System.currentTimeMillis() }
) : Translator {

    /**
     * This service's own record of the primary being too slow to wait for. Deliberately not part of
     * [GoogleEndpointHealth]: see [HedgeBudget] for why a missed hedge is not a primary failure.
     */
    private val hedgeBudget = HedgeBudget(clock)

    override val key: String = "google-translator"
    override val name: String = "Google Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    private val officialParser = createJsonParser<OfficialTranslateResponse>(pluginContext)
    private val translateParser = createJsonParser<TranslateResponse>(pluginContext)

    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    companion object {
        private const val TRANSLATE_PRIMARY = "https://translate.googleapis.com/translate_a/single"
        private const val TRANSLATE_FALLBACK = "https://clients5.google.com/translate_a/t"
        private const val TRANSLATE_OFFICIAL = "https://translation.googleapis.com/language/translate/v2"
        private const val FALLBACK_HOST = "clients5.google.com"

        /**
         * What to say about the primary when the circuit skipped it and the fallback then failed.
         *
         * The primary was never asked, because the circuit is open on recorded failures. Saying so
         * is better than leaving the user with a connection error from a host they may not even
         * know was involved, and it explains why the primary was not tried.
         */
        private const val PRIMARY_SKIPPED = "primary endpoint skipped after recent failures"
        private val TRANSLATE_FEATURES = listOf("t", "bd", "at", "ex", "ld", "md", "rw", "rm", "ss", "qc")

        /**
         * Two seconds: longer than an unthrottled primary needs for an ordinary translation, short
         * enough that a user waiting on a throttled one has not already given up. A request that
         * misses it is not abandoned, it is merely no longer alone.
         */
        private const val PRIMARY_HEDGE_MILLIS = 2_000L
    }

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        if (settings.translateApiKey.isNotBlank()) {
            pluginContext.logger.info("Using official Google Translate API")
            val officialResult = translateWithOfficialAPI(request)
            if (officialResult.isOk) return officialResult
            pluginContext.logger.info("Official API failed, falling back to unofficial endpoint")
        }
        return translateWithUnofficialAPI(request)
    }

    private suspend fun translateWithOfficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        // The official API rejects "source":"auto"; auto detection is requested by omitting it.
        val requestBody = buildMap {
            put("q", request.text)
            if (request.sourceLanguage != LanguageCode.AUTO) put("source", sourceTag)
            put("target", targetTag)
            put("format", "text")
        }

        val responseString = httpClient.sendJson(
            url = TRANSLATE_OFFICIAL,
            headers = apiConfig.createJsonHeaders(),
            body = requestBody,
            queryParams = mapOf("key" to settings.translateApiKey)
        ).bind()

        val parsed = officialParser.parse(responseString).bind()
        val firstTranslation = parsed.data.translations.firstOrNull()
            .toResultOr { ServiceError.InvalidResponseError("No translation in response", null) }
            .bind()

        TranslationResponse(
            translatedText = firstTranslation.translatedText,
            detectedLanguage = firstTranslation.detectedSourceLanguage?.let {
                languageMapper.fromProviderCode(it)
            }
        )
    }

    /**
     * Translates through the two unofficial endpoints.
     *
     * The fallback exists for a primary that throttles, so reaching it only after the primary's
     * whole request timeout has expired defeats it: the user waits for the endpoint that is not
     * answering before the one that can is even asked. The two paths therefore run against a hedge
     * budget instead of strictly one after the other. The fallback is asked as soon as the primary
     * fails on its own, or once the primary has missed [primaryHedgeMillis]; whichever path first
     * produces a translation wins.
     *
     * This is not a shortened network budget. The primary keeps its transport timeout, it is never
     * cancelled before it answers unless the fallback has already produced the translation, and if
     * the fallback cannot be reached the primary's answer is still the one returned. A host-blocked
     * fallback therefore cannot turn a slow but working primary into a failure.
     *
     * A primary that keeps missing the budget is remembered by [hedgeBudget], which is private to
     * this service, so after two misses the fallback is asked first and the primary is re-probed
     * later. That decision never touches [GoogleEndpointHealth], because the host does answer — just
     * later than this service's own patience — and the spell checker shares that circuit without a
     * second host to fall back to.
     */
    // `select` is stable; only its Deferred clause still carries the opt-in marker.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun translateWithUnofficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        // The primary has recently been too slow to be worth waiting for, so the fallback is asked
        // on its own instead of paying the hedge on every translation.
        if (hedgeBudget.prefersFallbackNow()) {
            pluginContext.logger.info("Primary endpoint slow recently, trying the fallback first")
            currentCoroutineContext().ensureActive()
            return translateWithFallbackFirst(request.text, sourceTag, targetTag)
        }

        val permit = endpointHealth.tryAcquirePrimary()
        if (permit == null) {
            pluginContext.logger.info("Primary endpoint unhealthy, using fallback")
            currentCoroutineContext().ensureActive()
            return tryFallbackEndpoint(request.text, sourceTag, targetTag).fold(
                success = { success -> Ok(success) },
                failure = { error -> Err(fallbackFailure(PRIMARY_SKIPPED, error)) }
            )
        }

        return coroutineScope {
            // The fallback waits for its turn instead of starting with the primary, so an ordinary
            // request costs one call, not two.
            val fallbackArmed = CompletableDeferred<Unit>()
            val primary = async { attemptPrimaryEndpoint(permit, request.text, sourceTag, targetTag) }
            val fallback = async {
                fallbackArmed.await()
                tryFallbackEndpoint(request.text, sourceTag, targetTag)
            }

            // A half-open probe is the one request that decides whether the circuit heals, so it is
            // never raced: hedging it would cancel the very answer the circuit is waiting for, and
            // a slow-but-working primary would keep reopening the cooldown the spell checker cannot
            // afford. Ordinary requests are hedged.
            val primaryResult =
                if (permit.isProbe) primary.await()
                else withTimeoutOrNull(primaryHedgeMillis) { primary.await() }

            if (primaryResult != null) {
                // A hedged primary that answered inside the budget clears this service's note about
                // it being slow. A probe's latency says nothing about the budget, so it is left
                // alone there.
                if (!permit.isProbe) hedgeBudget.recordPrimaryAnsweredInBudget()
                // The primary answered inside the budget. Either it is all that was needed, or it
                // failed for a reason the other host cannot fix; the fallback stays unasked.
                if (primaryResult.isOk || primaryResult.getError()!!.suppressesFallback()) {
                    fallback.cancel()
                    return@coroutineScope primaryResult
                }
                pluginContext.logger.info("Primary endpoint failed, trying fallback")
                fallbackArmed.complete(Unit)
                return@coroutineScope fallback.await().fold(
                    success = { success -> Ok(success) },
                    failure = { error -> Err(primaryResult.getError()!!.withFallbackFailure(error)) }
                )
            }

            pluginContext.logger.info(
                "Primary endpoint has not answered within ${primaryHedgeMillis}ms, asking the fallback"
            )
            fallbackArmed.complete(Unit)

            val firstAnswered: FirstAnswered = select {
                primary.onAwait { FirstAnswered.Primary(it) }
                fallback.onAwait { FirstAnswered.Fallback(it) }
            }

            when (firstAnswered) {
                is FirstAnswered.Primary -> {
                    if (firstAnswered.result.isOk) {
                        // The fallback's request is no longer useful; it may only just have started.
                        fallback.cancel()
                        return@coroutineScope firstAnswered.result
                    }
                    return@coroutineScope fallback.await().fold(
                        success = { success -> Ok(success) },
                        failure = { error -> Err(firstAnswered.result.getError()!!.withFallbackFailure(error)) }
                    )
                }
                is FirstAnswered.Fallback -> {
                    // A primary answer that is already in hand wins over the fallback's, so a slow
                    // primary that did answer is never discarded in favour of the secondary host.
                    if (primary.isCompleted && !primary.isCancelled) {
                        val primaryNow = primary.getCompleted()
                        if (primaryNow.isOk) return@coroutineScope primaryNow
                        return@coroutineScope firstAnswered.result.fold(
                            success = { success -> Ok(success) },
                            failure = { error -> Err(primaryNow.getError()!!.withFallbackFailure(error)) }
                        )
                    }
                    if (firstAnswered.result.isOk) {
                        // The primary missed the budget while the fallback served the request. That
                        // is a fact about this service's patience, not about the endpoint: the host
                        // is answering, only later than the hedge. It is therefore noted privately,
                        // so after two misses the fallback is asked first and the primary is
                        // re-probed later, and never recorded as a primary failure on the circuit
                        // the spell checker shares.
                        currentCoroutineContext().ensureActive()
                        hedgeBudget.recordMiss()
                        primary.cancel()
                        return@coroutineScope firstAnswered.result
                    }
                    // Neither path has produced a usable answer yet, and the primary still might.
                    return@coroutineScope primary.await().fold(
                        success = { success -> Ok(success) },
                        failure = { error -> Err(error.withFallbackFailure(firstAnswered.result.getError()!!)) }
                    )
                }
            }
        }
    }

    /**
     * Serves a request while [hedgeBudget] says the primary is too slow to wait for.
     *
     * The fallback is asked on its own, exactly as it would be with the circuit open, so the request
     * costs one call and no hedge. The primary is still reachable: if the fallback cannot serve the
     * request, the slow primary is asked directly and its answer is the one returned, so an
     * unreachable fallback (#365) can never fail a request the primary could have served.
     */
    private suspend fun translateWithFallbackFirst(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> {
        val fallbackResult = tryFallbackEndpoint(text, sourceTag, targetTag)
        if (fallbackResult.isOk) return fallbackResult

        pluginContext.logger.info("Fallback endpoint failed, trying the slow primary")
        currentCoroutineContext().ensureActive()
        val fallbackError = fallbackResult.getError()!!
        // The circuit may be open on genuine failures, in which case the primary really was skipped.
        val permit = endpointHealth.tryAcquirePrimary()
            ?: return Err(fallbackFailure(PRIMARY_SKIPPED, fallbackError))

        return attemptPrimaryEndpoint(permit, text, sourceTag, targetTag).fold(
            success = { success ->
                // The fallback is not serving requests, so it is no longer the cheaper path and the
                // note that steered requests towards it is dropped.
                hedgeBudget.recordFallbackUnusable()
                Ok(success)
            },
            failure = { error -> Err(error.withFallbackFailure(fallbackError)) }
        )
    }

    /** Which of the two unofficial paths answered first once both were live. */
    private sealed interface FirstAnswered {
        class Primary(val result: Result<TranslationResponse, ServiceError>) : FirstAnswered
        class Fallback(val result: Result<TranslationResponse, ServiceError>) : FirstAnswered
    }

    /**
     * Runs the primary attempt and records its outcome on the circuit.
     *
     * The permit is released on every path exactly once, including cancellation and any error
     * escaping the primary attempt, so an abandoned probe never strands the circuit.
     */
    private suspend fun attemptPrimaryEndpoint(
        permit: GoogleEndpointHealth.Permit,
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> {
        try {
            val primaryResult = tryPrimaryEndpoint(text, sourceTag, targetTag)
            primaryResult.fold(
                success = {
                    currentCoroutineContext().ensureActive()
                    endpointHealth.recordSuccess(permit)
                },
                failure = { error ->
                    currentCoroutineContext().ensureActive()
                    if (error.isRetryable) endpointHealth.recordTransientFailure(permit, error)
                }
            )
            return primaryResult
        } finally {
            endpointHealth.releasePrimary(permit)
        }
    }

    /**
     * The error a caller is left with when the fallback failed after the primary had already failed.
     *
     * Returning the fallback's failure on its own is what made a blocked fallback host look like a
     * bare connection error: the message said the connection was refused and nothing said that the
     * primary had already failed, or which host was refused. The fallback's own error type is kept,
     * since that is the failure the caller is left with and its classification drives their next
     * action, and both stages are stated separately so a throttle, a timeout and an unreachable
     * host stay distinguishable.
     */
    private fun ServiceError.withFallbackFailure(fallbackError: ServiceError): ServiceError =
        fallbackFailure("primary endpoint ${describe()}", fallbackError)

    private fun fallbackFailure(primaryClause: String, fallbackError: ServiceError): ServiceError {
        // The first line is what the status bar shows, so it carries the classification of both
        // stages; the fallback's own detail follows on its own line.
        val summary = "Google Translate: $primaryClause, fallback $FALLBACK_HOST ${fallbackError.describe()}"
        val detail = buildString {
            append("Fallback detail: ")
            append(fallbackError.message.lineSequence().first())
            if (fallbackError.isReachabilityFailure()) {
                append('\n')
                append(
                    "A hosts-file entry, firewall, VPN or ad blocker that blocks $FALLBACK_HOST " +
                        "would also produce this; the primary endpoint is retried automatically."
                )
            }
        }
        return fallbackError.withMessage("$summary\n$detail")
    }

    /** Whether the failure is about reaching the host at all, rather than about its answer. */
    private fun ServiceError.isReachabilityFailure(): Boolean = when (this) {
        is ServiceError.NetworkError, is ServiceError.TimeoutError -> true
        else -> false
    }

    /** A short, factual label for one stage's failure, for a message that quotes two of them. */
    private fun ServiceError.describe(): String = when (this) {
        is ServiceError.NetworkError -> "unreachable"
        is ServiceError.TimeoutError -> "timed out"
        is ServiceError.RateLimitError -> "rate limited"
        is ServiceError.ServiceUnavailableError -> "unavailable"
        is ServiceError.InvalidResponseError -> "returned an unusable response"
        is ServiceError.AuthenticationError -> "rejected the credentials"
        is ServiceError.ConfigurationError -> "is not configured"
        is ServiceError.InvalidInputError, is ServiceError.ValidationError -> "rejected the request"
        is ServiceError.UnsupportedLanguageError -> "does not support that language"
        is ServiceError.UnknownError -> "failed"
    }

    /** The same error with a rewritten message, keeping its type and the data it carries. */
    private fun ServiceError.withMessage(message: String): ServiceError = when (this) {
        is ServiceError.NetworkError -> copy(message = message)
        is ServiceError.TimeoutError -> copy(message = message)
        is ServiceError.RateLimitError -> copy(message = message)
        is ServiceError.ServiceUnavailableError -> copy(message = message)
        is ServiceError.AuthenticationError -> copy(message = message)
        is ServiceError.ConfigurationError -> copy(message = message)
        is ServiceError.InvalidResponseError -> copy(message = message)
        is ServiceError.InvalidInputError -> copy(message = message)
        is ServiceError.ValidationError -> copy(message = message)
        is ServiceError.UnsupportedLanguageError -> copy(message = message)
        is ServiceError.UnknownError -> copy(message = message)
    }

    /**
     * Whether a primary failure is about the request itself rather than the endpoint's reachability.
     * The request would fail identically against the fallback, so these errors are returned as-is
     * and leave the circuit untouched. Every other error, including a 200 response whose body the
     * parser cannot decode, still falls back, because the secondary host speaks a different protocol
     * and may well serve it.
     */
    private fun ServiceError.suppressesFallback(): Boolean = when (this) {
        is ServiceError.AuthenticationError,
        is ServiceError.InvalidInputError,
        is ServiceError.ValidationError,
        is ServiceError.UnsupportedLanguageError,
        is ServiceError.ConfigurationError -> true
        else -> false
    }

    private suspend fun tryPrimaryEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val responseString = httpClient.getOnce(
            url = TRANSLATE_PRIMARY,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "gtx",
                "ie" to "UTF-8",
                "oe" to "UTF-8",
                "dj" to 1,
                "dt" to TRANSLATE_FEATURES,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        val parsed = translateParser.parse(responseString).bind()
        val translatedText = parsed.sentences.joinToString("") { it.text.orEmpty() }
        val detectedLang = languageMapper.fromProviderCode(parsed.sourceLanguage)
        val alternatives = parsed.dictionary?.firstOrNull()?.terms?.take(3) ?: emptyList()

        TranslationResponse(
            translatedText = translatedText.trim(),
            detectedLanguage = detectedLang,
            alternatives = alternatives
        )
    }

    private suspend fun tryFallbackEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val responseString = httpClient.get(
            url = TRANSLATE_FALLBACK,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "dict-chrome-ex",
                "dj" to 1,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        parseFallbackResponse(responseString).bind()
    }

    /**
     * Parses the fallback endpoint's response, which is not documented and has been observed in two
     * array shapes. The payload is inspected field by field rather than decoded into a fixed type, so
     * an unexpected shape fails normally instead of throwing while indexing.
     */
    private fun parseFallbackResponse(
        responseString: String
    ): Result<TranslationResponse, ServiceError> {
        val root = runCatching { PluginJson.parseToJsonElement(responseString) }
            .getOrElse { return Err(ServiceError.InvalidResponseError("Fallback response is not valid JSON", it)) }

        val fields = fallbackFields(root)
            ?: return Err(ServiceError.InvalidResponseError("Unsupported fallback response shape", null))

        val translatedText = fields.firstOrNull()?.stringOrNull()?.trim()
        if (translatedText.isNullOrEmpty()) {
            return Err(ServiceError.InvalidResponseError("No translation in fallback response", null))
        }

        val detectedLanguage = fields.getOrNull(1)
            ?.stringOrNull()
            ?.let { languageMapper.fromProviderCode(it) }

        return Ok(TranslationResponse(translatedText = translatedText, detectedLanguage = detectedLanguage))
    }

    /**
     * Extracts the flat field list from the two known fallback shapes: a nested
     * `[[translated, src, ...]]` or a flat `[translated, src, ...]`. Any other shape yields null.
     */
    private fun fallbackFields(root: JsonElement): List<JsonElement>? = when (root) {
        is JsonArray -> when (val first = root.firstOrNull()) {
            is JsonArray -> first.toList()
            is JsonPrimitive -> root.toList()
            else -> null
        }
        else -> null
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

}

/**
 * How often this translator is willing to wait for a primary that keeps missing its hedge budget.
 *
 * "The primary did not answer within [GoogleTranslatorService]'s hedge budget" is not a primary
 * failure. The host answers, only later than this service is willing to make a user wait, so
 * recording it on the shared [GoogleEndpointHealth] circuit would take every other consumer of that
 * circuit down with it — and `GoogleSpellCheckerService` has no second host: a permit it cannot get
 * is a failed spell check. On a network where the primary is merely slow, that would trade working
 * translations for permanently unavailable spell checking.
 *
 * So the translator keeps its own bounded note instead. Two consecutive misses make it ask the
 * fallback first for [DIRECT_FALLBACK_MILLIS]; after that the primary is asked again, hedged as
 * usual, so a primary that speeds up is picked up again on the next re-probe. Any primary answer
 * inside the budget — or a fallback that cannot serve the request — clears the note.
 *
 * The quiet period is fixed rather than doubling: the re-probe costs one request, so there is no
 * reason to keep a recovered primary unused for minutes. State is atomic because translations run
 * concurrently; the counters are advisory and a lost update only changes the next scheduling
 * decision, never a result.
 */
private class HedgeBudget(private val clock: () -> Long) {

    private val consecutiveMisses = AtomicInteger(0)
    private val directFallbackUntilMillis = AtomicLong(0L)

    /** Whether the fallback should be asked first right now. */
    fun prefersFallbackNow(): Boolean =
        consecutiveMisses.get() >= MISS_THRESHOLD && clock() < directFallbackUntilMillis.get()

    /** The primary answered inside the hedge budget, so it is worth waiting for again. */
    fun recordPrimaryAnsweredInBudget() = clear()

    /** The fallback could not serve a request, so preferring it is pointless. */
    fun recordFallbackUnusable() = clear()

    /** The primary missed the hedge budget and the fallback served the request instead. */
    fun recordMiss() {
        if (consecutiveMisses.incrementAndGet() >= MISS_THRESHOLD) {
            directFallbackUntilMillis.set(clock() + DIRECT_FALLBACK_MILLIS)
        }
    }

    private fun clear() {
        consecutiveMisses.set(0)
        directFallbackUntilMillis.set(0L)
    }

    private companion object {
        const val MISS_THRESHOLD = 2

        /** Long enough to spare a throttled primary a hedge on every translation, short enough
         *  that a primary which recovers is back in use within half a minute. */
        const val DIRECT_FALLBACK_MILLIS = 30_000L
    }
}
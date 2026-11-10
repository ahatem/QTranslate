package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.MultipartHttpClient
import com.github.ahatem.qtranslate.api.plugin.MultipartPart
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.SpeechAudio
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins what each provider is sent and how its reply is read, since a provider that silently
 * gets the wrong URL or field name fails only for the users of that provider.
 */
class SpeechClientTest {

    private class Recorded(val url: String, val headers: Map<String, String>, val fields: Map<String, String>, val query: Map<String, Any?>)

    /** Answers with [replies] in order and records every upload. */
    private class FakeUploads(replies: List<Result<String, ServiceError>> = emptyList()) : MultipartHttpClient {
        private val queue = ArrayDeque(replies)
        val requests = mutableListOf<Recorded>()

        override suspend fun postMultipart(url: String, parts: List<MultipartPart>, headers: Map<String, String>, queryParams: Map<String, Any?>) =
            next(Recorded(url, headers, parts.filterIsInstance<MultipartPart.Text>().associate { it.name to it.value }, queryParams))

        override suspend fun postBytes(url: String, body: ByteArray, contentType: String, headers: Map<String, String>, queryParams: Map<String, Any?>): Result<String, ServiceError> {
            if (contentType == "application/json") bodies += body.toString(Charsets.UTF_8)
            return next(Recorded(url, headers + ("Content-Type" to contentType), emptyMap(), queryParams))
        }

        private fun next(request: Recorded): Result<String, ServiceError> {
            requests += request
            return queue.removeFirst()
        }

        override suspend fun get(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>) =
            next(Recorded(url, headers, emptyMap(), queryParams))
        val bodies = mutableListOf<String>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String?, queryParams: Map<String, Any?>) = error("unused")
        override suspend fun getBytes(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>) = error("unused")
        override suspend fun postForm(url: String, formData: Map<String, String>, headers: Map<String, String>, queryParams: Map<String, Any?>, cookies: Map<String, String>) = error("unused")
        override suspend fun postFormBytes(url: String, formData: Map<String, String>, headers: Map<String, String>, queryParams: Map<String, Any?>, cookies: Map<String, String>) = error("unused")
    }

    private val clip = SpeechAudio(SpeechClient.silentWav(500))

    private fun client(http: FakeUploads, settings: SpeechSettings) =
        SpeechClient(FakePluginContext(httpClient = http), http) { settings }

    @Test
    fun `groq gets the OpenAI-style request with word timings and the language code`() = runBlocking {
        val http = FakeUploads(listOf(Ok("""{"text":" hello there ","language":"english","words":[{"word":"hello","start":0.1,"end":0.4}]}""")))
        val result = client(http, SpeechSettings(provider = "Groq", apiKey = "gsk_x"))
            .transcribe(SpeechRecognitionRequest(clip, LanguageCode("en-US"), prompt = "earlier"))

        val request = http.requests.single()
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", request.url)
        assertEquals("Bearer gsk_x", request.headers["Authorization"])
        assertEquals("whisper-large-v3-turbo", request.fields["model"])
        assertEquals("verbose_json", request.fields["response_format"])
        assertEquals("word", request.fields["timestamp_granularities[]"])
        assertEquals("en", request.fields["language"])
        assertEquals("earlier", request.fields["prompt"])

        val response = result.get() ?: error("expected Ok, got $result")
        assertEquals("hello there", response.text)
        assertEquals(100, response.words.single().startMillis)
    }

    @Test
    fun `a server that refuses the verbose format is asked again in plain json and remembers capability`() = runBlocking {
        val http = FakeUploads(listOf(
            Err(ServiceError.InvalidResponseError("response_format verbose_json is not supported")),
            Ok("""{"text":"plain"}"""),
            Ok("""{"text":"second"}""")
        ))
        val client = client(http, SpeechSettings(provider = "Local Whisper server"))
        val result = client.transcribe(SpeechRecognitionRequest(clip))

        assertEquals(listOf("verbose_json", "json"), http.requests.map { it.fields["response_format"] })
        assertEquals("http://localhost:8000/v1/audio/transcriptions", http.requests.first().url)
        assertTrue(http.requests.first().headers.isEmpty(), "no key, no Authorization header")
        assertEquals("plain", result.get()?.text)
        assertEquals(2, http.requests.size)

        // Subsequent call skips verbose_json and directly requests json
        val secondResult = client.transcribe(SpeechRecognitionRequest(clip))
        assertEquals("second", secondResult.get()?.text)
        assertEquals(3, http.requests.size)
        assertEquals("json", http.requests.last().fields["response_format"])
    }

    @Test
    fun `an auth failure is not retried`() = runBlocking {
        val http = FakeUploads(listOf(Err(ServiceError.AuthenticationError("bad key"))))
        client(http, SpeechSettings(provider = "OpenAI", apiKey = "sk")).transcribe(SpeechRecognitionRequest(clip))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `deepgram gets the audio as the body with its own auth scheme and query`() = runBlocking {
        val http = FakeUploads(listOf(Ok("""{"results":{"channels":[{"detected_language":"ar","alternatives":[{"transcript":"مرحبا","words":[{"word":"مرحبا","punctuated_word":"مرحبا.","start":0.2,"end":0.7}]}]}]}}""")))
        val result = client(http, SpeechSettings(provider = "Deepgram", apiKey = "dg")).transcribe(SpeechRecognitionRequest(clip))

        val request = http.requests.single()
        assertEquals("https://api.deepgram.com/v1/listen", request.url)
        assertEquals("Token dg", request.headers["Authorization"])
        assertEquals("audio/wav", request.headers["Content-Type"])
        assertEquals("nova-3", request.query["model"])
        assertEquals("true", request.query["detect_language"])

        val response = result.get()!!
        assertEquals("مرحبا", response.text)
        assertEquals(LanguageCode("ar"), response.detectedLanguage)
        assertEquals("مرحبا.", response.words.single().text)
    }

    @Test
    fun `a custom provider uses the user's address and model`() = runBlocking {
        val http = FakeUploads(listOf(Ok("""{"text":"ok"}""")))
        client(http, SpeechSettings(provider = "Custom (OpenAI-compatible)", baseUrl = "http://box:9000/v1/", model = "large-v3"))
            .transcribe(SpeechRecognitionRequest(clip))
        assertEquals("http://box:9000/v1/audio/transcriptions", http.requests.single().url)
        assertEquals("large-v3", http.requests.single().fields["model"])
    }

    @Test
    fun `an audio chat model gets the clip as input_audio in a chat request`() = runBlocking {
        val http = FakeUploads(listOf(Ok("""{"choices":[{"message":{"content":"\"see you tomorrow\""}}]}""")))
        val result = client(http, SpeechSettings(
            provider = "Audio chat model (OpenAI-compatible)",
            baseUrl = "http://192.168.1.5:20128/v1", apiKey = "k", model = "gemini-flash"
        )).transcribe(SpeechRecognitionRequest(clip, LanguageCode("ar")))

        val request = http.requests.single()
        assertEquals("http://192.168.1.5:20128/v1/chat/completions", request.url)
        assertEquals("Bearer k", request.headers["Authorization"])
        val body = http.bodies.single()
        assertTrue("\"input_audio\"" in body && "\"format\":\"wav\"" in body && "\"model\":\"gemini-flash\"" in body)
        assertTrue("'ar'" in body, "the language hint is in the instruction")
        assertEquals("see you tomorrow", result.get()?.text, "surrounding quotes are dropped")
    }

    @Test
    fun `an audio chat model needs a model name`() = runBlocking {
        val error = client(FakeUploads(), SpeechSettings(provider = "Audio chat model (OpenAI-compatible)", baseUrl = "http://x/v1"))
            .transcribe(SpeechRecognitionRequest(clip)).getError()
        assertTrue(error is ServiceError.ConfigurationError && "Model" in error.message)
    }

    @Test
    fun `the connection test checks the model list and sends silent clip`() = runBlocking {
        val listing = Ok("""{"data":[{"id":"antigravity/gemini-3.8-flash-tiered"},{"id":"groq/whisper-large-v3-turbo"}]}""")
        val chatResponse = Ok("""{"choices":[{"message":{"content":"silence"}}]}""")
        val settings = SpeechSettings(provider = "Audio chat model (OpenAI-compatible)", baseUrl = "http://gw:20128/v1", apiKey = "k",
            model = "antigravity/gemini-3.8-flash-tiered")
        val http = FakeUploads(listOf(listing, chatResponse))
        assertTrue(client(http, settings).testConnection().isOk)
        assertEquals("http://gw:20128/v1/models", http.requests[0].url)
        assertEquals("Bearer k", http.requests[0].headers["Authorization"])
        assertEquals("http://gw:20128/v1/chat/completions", http.requests[1].url)
    }

    @Test
    fun `testConnection reports key rejected on 401`() = runBlocking {
        val http = FakeUploads(listOf(Err(ServiceError.AuthenticationError("HTTP 401 Unauthorized"))))
        val settings = SpeechSettings(provider = "OpenAI", apiKey = "bad-key", model = "whisper-1")
        val err = client(http, settings).testConnection().getError()
        assertNotNull(err)
        assertTrue(err is ServiceError.AuthenticationError)
        assertEquals(SpeechLocalization.get("test_key_rejected"), err.message)
    }

    @Test
    fun `testConnection reports model unknown when listing excludes it`() = runBlocking {
        val listing = Ok("""{"data":[{"id":"whisper-1"}]}""")
        val http = FakeUploads(listOf(listing))
        val settings = SpeechSettings(provider = "OpenAI", apiKey = "key", model = "nonexistent-model")
        val err = client(http, settings).testConnection().getError()
        assertNotNull(err)
        assertTrue(err.message.contains("nonexistent-model"))
    }

    @Test
    fun `testConnection reports model does not accept audio when clip upload fails with modality error`() = runBlocking {
        val listing = Ok("""{"data":[{"id":"text-only-model"}]}""")
        val clipErr = Err(ServiceError.ValidationError("The model 'text-only-model' does not support audio modality."))
        val http = FakeUploads(listOf(listing, clipErr))
        val settings = SpeechSettings(provider = "OpenAI", apiKey = "key", model = "text-only-model")
        val err = client(http, settings).testConnection().getError()
        assertNotNull(err)
        assertEquals(SpeechLocalization.get("test_model_not_audio", "text-only-model"), err.message)
    }

    @Test
    fun `testConnection reports server unreachable on network error`() = runBlocking {
        val http = FakeUploads(listOf(Err(ServiceError.NetworkError("Connection refused"))))
        val settings = SpeechSettings(provider = "OpenAI", apiKey = "key", model = "whisper-1")
        val err = client(http, settings).testConnection().getError()
        assertNotNull(err)
        assertTrue(err is ServiceError.NetworkError)
        assertTrue(err.message.contains("Connection refused"))
    }

    @Test
    fun `non-format 400 error is not retried in plain json`() = runBlocking {
        val http = FakeUploads(listOf(
            Err(ServiceError.ValidationError("File size exceeds 25MB limit"))
        ))
        val client = client(http, SpeechSettings(provider = "OpenAI", apiKey = "sk", model = "whisper-1"))
        val result = client.transcribe(SpeechRecognitionRequest(clip))

        assertTrue(result.getError() != null)
        assertEquals(1, http.requests.size, "Non-format 400 error must not trigger a retry")
    }

    @Test
    fun `audio chat model response strips wrappers and refuses excuses`() = runBlocking {
        val http = FakeUploads(listOf(Ok("""{"choices":[{"message":{"content":"Transcript: ```Hello world```"}}]}""")))
        val client = client(http, SpeechSettings(provider = "Audio chat model (OpenAI-compatible)", baseUrl = "http://x/v1", model = "m"))
        val result = client.transcribe(SpeechRecognitionRequest(clip)).get()
        assertEquals("Hello world", result?.text)

        // Real spoken speech starting with "I can't hear you" must NOT be refused
        val speechHttp = FakeUploads(listOf(Ok("""{"choices":[{"message":{"content":"I can't hear you, can you repeat?"}}]}""")))
        val speechClient = client(speechHttp, SpeechSettings(provider = "Audio chat model (OpenAI-compatible)", baseUrl = "http://x/v1", model = "m"))
        val speechResult = speechClient.transcribe(SpeechRecognitionRequest(clip)).get()
        assertEquals("I can't hear you, can you repeat?", speechResult?.text)

        // Model refusal about transcription capability must be rejected
        val excuseHttp = FakeUploads(listOf(Ok("""{"choices":[{"message":{"content":"I cannot transcribe audio files."}}]}""")))
        val excuseClient = client(excuseHttp, SpeechSettings(provider = "Audio chat model (OpenAI-compatible)", baseUrl = "http://x/v1", model = "m"))
        val excuseError = excuseClient.transcribe(SpeechRecognitionRequest(clip)).getError()
        assertTrue(excuseError is ServiceError.ValidationError)
    }

    @Test
    fun `a server without a model list still passes the connection test`() = runBlocking {
        val listingErr = Err(ServiceError.ServiceUnavailableError("HTTP 404 for http://localhost:8000/v1/models"))
        val transcribeOk = Ok("""{"text":"hello"}""")
        val http = FakeUploads(listOf(listingErr, transcribeOk))
        assertTrue(client(http, SpeechSettings(provider = "Local Whisper server")).testConnection().isOk)
    }

    @Test
    fun `settings problems are reported before anything is sent`() = runBlocking {
        val http = FakeUploads()
        val noKey = client(http, SpeechSettings(provider = "Groq")).transcribe(SpeechRecognitionRequest(clip)).getError()
        assertTrue(noKey is ServiceError.ConfigurationError && "API key" in noKey.message)
        val noUrl = client(http, SpeechSettings(provider = "Custom (OpenAI-compatible)")).transcribe(SpeechRecognitionRequest(clip)).getError()
        assertTrue(noUrl is ServiceError.ConfigurationError && "Server URL" in noUrl.message)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `language names and codes convert both ways`() {
        assertEquals("zh", SpeechClient.primaryTag(LanguageCode("zh-Hans")))
        assertEquals(null, SpeechClient.primaryTag(LanguageCode.AUTO))
        assertEquals(LanguageCode("ar"), SpeechClient.languageFromName("Arabic"))
        assertEquals(LanguageCode("en"), SpeechClient.languageFromName("english"))
        assertEquals(null, SpeechClient.languageFromName("klingon"))
    }

    @Test
    fun `none keeps live text only and sends nothing`() = runBlocking {
        val http = FakeUploads()
        val error = client(http, SpeechSettings(provider = "None (live text only)")).transcribe(SpeechRecognitionRequest(clip)).getError()
        assertTrue(error is ServiceError.ConfigurationError)
        assertTrue(client(http, SpeechSettings(provider = "None (live text only)")).testConnection().isOk)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `every dropdown option maps to its provider`() {
        val labels = SpeechProvider.DROPDOWN_OPTIONS.split(",")
        assertEquals(SpeechProvider.entries.map { it.label }, labels)
        labels.forEach { assertEquals(it, SpeechProvider.fromLabel(it).label) }
    }
}

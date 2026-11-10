package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoskBuiltinServiceTest {

    @TempDir
    lateinit var tempDir: File

    open class FakeRecognizerHandle(
        private val onClosed: () -> Unit = {}
    ) : VoskRecognizerHandle {
        val closed = AtomicBoolean(false)
        val accepts = AtomicInteger(0)
        var returnEndpoint = false
        var partialJson = """{"partial":"hello world"}"""
        var resultJson = """{"text":"hello world","result":[{"conf":1.0,"start":0.2,"end":0.8,"word":"hello"}]}"""
        var finalJson = """{"text":"final speech","result":[{"conf":1.0,"start":1.0,"end":1.5,"word":"speech"}]}"""

        override fun setWords(words: Boolean) {}

        override fun acceptWaveForm(data: ByteArray, len: Int): Boolean {
            accepts.incrementAndGet()
            return returnEndpoint
        }

        override fun getResult(): String = resultJson
        override fun getPartialResult(): String = partialJson
        override fun getFinalResult(): String = finalJson
        override fun reset() {}

        override fun close() {
            closed.set(true)
            onClosed()
        }
    }

    class FakeModelHandle(
        private val onClose: () -> Unit = {}
    ) : VoskModelHandle {
        val recognizers = mutableListOf<FakeRecognizerHandle>()
        val closed = AtomicBoolean(false)

        override fun createRecognizer(sampleRate: Float): VoskRecognizerHandle {
            val r = FakeRecognizerHandle()
            recognizers.add(r)
            return r
        }

        override fun close() {
            closed.set(true)
            onClose()
        }
    }

    @Test
    fun `recognizer lifecycle emits partial then final on finish`() = runBlocking {
        val recognizer = FakeRecognizerHandle()
        val session = VoskBuiltinSession(recognizer, this)

        val eventsList = mutableListOf<LiveSpeechEvent>()
        val collectJob = launch {
            session.events.collect { eventsList.add(it) }
        }

        // Send PCM that doesn't trigger endpoint -> partial
        recognizer.returnEndpoint = false
        recognizer.partialJson = """{"partial":"testing"}"""
        session.sendAudio(ByteArray(320))

        // Wait a brief moment for background processor
        kotlinx.coroutines.delay(50)
        assertTrue(eventsList.any { it is LiveSpeechEvent.Partial && it.text == "testing" })

        // Send PCM that triggers endpoint -> final
        recognizer.returnEndpoint = true
        recognizer.resultJson = """{"text":"testing completed","result":[{"conf":1.0,"start":0.1,"end":0.5,"word":"testing"}]}"""
        session.sendAudio(ByteArray(320))

        kotlinx.coroutines.delay(50)
        val finalEvent = eventsList.filterIsInstance<LiveSpeechEvent.Final>().firstOrNull()
        assertNotNull(finalEvent)
        assertEquals("Testing completed", finalEvent.text)
        assertEquals(1, finalEvent.words.size)
        assertEquals("testing", finalEvent.words[0].text)
        assertEquals(100L, finalEvent.words[0].startMillis)
        assertEquals(500L, finalEvent.words[0].endMillis)

        // Finish session flushes final result
        recognizer.finalJson = """{"text":"done"}"""
        session.finish()
        collectJob.join()

        assertTrue(recognizer.closed.get())
        assertTrue(eventsList.any { it is LiveSpeechEvent.Final && it.text == "Done" })
    }

    @Test
    fun `builtin session runs recognition on a dedicated worker thread`() = runBlocking {
        val threadNames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val recognizer = object : FakeRecognizerHandle() {
            override fun acceptWaveForm(data: ByteArray, len: Int): Boolean {
                threadNames.add(Thread.currentThread().name)
                return super.acceptWaveForm(data, len)
            }
        }
        val session = VoskBuiltinSession(recognizer)
        session.sendAudio(ByteArray(320))
        kotlinx.coroutines.delay(100)
        session.finish()

        assertTrue(threadNames.isNotEmpty())
        assertTrue(threadNames.all { it.startsWith("vosk-builtin-worker") })
    }

    private fun writeFakeModelFiles(dir: File) {
        File(dir, "am").mkdirs()
        File(dir, "am/final.mdl").writeText("test-model")
        File(dir, "conf").mkdirs()
        File(dir, "conf/model.conf").writeText("sample_rate=16000")
        File(dir, "graph").mkdirs()
        File(dir, "graph/HCLG.fst").writeText("test-graph")
    }

    @Test
    fun `builtin service caches models per language and closes them on shutdown`() = runBlocking {
        val manager = VoskModelManager({ tempDir })
        // Create model directory on disk
        val modelDir = File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        writeFakeModelFiles(modelDir)

        val modelCreates = AtomicInteger(0)
        var lastCreatedModel: FakeModelHandle? = null

        val service = VoskBuiltinService(
            modelManager = manager,
            settings = {
                SpeechSettings(
                    liveEngine = LIVE_VOSK_BUILTIN,
                    voskBuiltinModel = "vosk-model-small-en-us-0.15"
                )
            },
            modelFactory = {
                modelCreates.incrementAndGet()
                FakeModelHandle().also { lastCreatedModel = it }
            }
        )

        // Validate succeeds because model is installed
        assertNull(service.validate().getError(), "Validate succeeds when model is installed")

        // Open first session
        val session1 = service.openSession(LanguageCode.ENGLISH).get()
        assertNotNull(session1)
        assertEquals(1, modelCreates.get(), "First session loads model")

        // Open second session for same language -> model is reused from cache
        val session2 = service.openSession(LanguageCode.ENGLISH).get()
        assertNotNull(session2)
        assertEquals(1, modelCreates.get(), "Second session reuses cached model")

        session1.cancel()
        session2.cancel()

        assertFalse(lastCreatedModel?.closed?.get() == true, "Model remains open after individual sessions end")

        service.close()
        assertTrue(lastCreatedModel?.closed?.get() == true, "Model closes when service is closed")
    }

    @Test
    fun `transcribeClip transcribes finished audio clip offline`() = runBlocking {
        val manager = VoskModelManager({ tempDir })
        File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply {
            mkdirs()
            writeFakeModelFiles(this)
        }

        val fakeRecognizer = FakeRecognizerHandle().apply {
            finalJson = """{"text":"test offline speech","result":[{"conf":1.0,"start":0.1,"end":0.6,"word":"test"},{"conf":1.0,"start":0.7,"end":1.2,"word":"offline"}]}"""
        }

        val service = VoskBuiltinService(
            modelManager = manager,
            settings = {
                SpeechSettings(
                    mode = MODE_LOCAL,
                    liveEngine = LIVE_VOSK_BUILTIN,
                    voskBuiltinModel = "vosk-model-small-en-us-0.15"
                )
            },
            modelFactory = {
                object : VoskModelHandle {
                    override fun createRecognizer(sampleRate: Float): VoskRecognizerHandle = fakeRecognizer
                    override fun close() {}
                }
            }
        )

        val response = service.transcribeClip(ByteArray(1600), LanguageCode.ENGLISH).get()
        assertNotNull(response)
        assertEquals("Test offline speech", response.text)
        assertEquals(LanguageCode.ENGLISH, response.detectedLanguage)
        assertEquals(2, response.words.size)
        assertEquals("test", response.words[0].text)
        assertTrue(fakeRecognizer.closed.get(), "Recognizer is closed after transcribeClip completes")
    }

    @Test
    fun `fixMojibake restores Windows-1252 corrupted UTF-8 Arabic text`() {
        val corrupted1 = "Ø¬Ø§ÙŠÙ‡ Ø³Ø§Ù„Ø¨ Ø¹Ø±Ø¨ÙŠ"
        assertEquals("جايه سالب عربي", fixMojibake(corrupted1))

        val plainEnglish = "hello world"
        assertEquals(plainEnglish, fixMojibake(plainEnglish))
    }

    @Test
    fun `formatVoskText capitalizes English sentences and I pronouns`() {
        val raw = "i think i'm ready to go"
        assertEquals("I think I'm ready to go", formatVoskText(raw, "en"))

        val arabic = "السلام عليكم ورحمة الله"
        assertEquals("السلام عليكم ورحمة الله", formatVoskText(arabic, "ar"))
    }

    @Test
    fun `model cache caps at 2 models and keys by model name`() {
        val manager = VoskModelManager({ tempDir })
        // Create 3 model directories
        val m1 = File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs(); writeFakeModelFiles(this) }
        val m2 = File(manager.modelsDirectory, "vosk-model-small-de-0.15").apply { mkdirs(); writeFakeModelFiles(this) }
        val m3 = File(manager.modelsDirectory, "vosk-model-small-fr-0.22").apply { mkdirs(); writeFakeModelFiles(this) }

        val createdModels = mutableMapOf<String, FakeModelHandle>()
        val service = VoskBuiltinService(
            modelManager = manager,
            settings = { SpeechSettings() },
            modelFactory = { dir ->
                FakeModelHandle().also { createdModels[dir.name] = it }
            }
        )

        // Preload model 1
        service.preloadModel("vosk-model-small-en-us-0.15")
        assertEquals(1, service.cachedModelCount)
        assertTrue(service.isModelCached("vosk-model-small-en-us-0.15"))

        // Preload model 2
        service.preloadModel("vosk-model-small-de-0.15")
        assertEquals(2, service.cachedModelCount)
        assertTrue(service.isModelCached("vosk-model-small-de-0.15"))

        // Preload model 3 -> should evict model 1 (keeping at most 2)
        service.preloadModel("vosk-model-small-fr-0.22")
        assertEquals(2, service.cachedModelCount, "Cache should cap at 2 models")
        assertTrue(service.isModelCached("vosk-model-small-fr-0.22"))
        assertTrue(createdModels["vosk-model-small-en-us-0.15"]?.closed?.get() == true, "Evicted model should be closed")

        service.close()
        assertEquals(0, service.cachedModelCount)
    }

    @Test
    fun `onSelectedModelChanged unloads unselected models unless in use by active session`() = runBlocking {
        val manager = VoskModelManager({ tempDir })
        val m1 = File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs(); writeFakeModelFiles(this) }
        val m2 = File(manager.modelsDirectory, "vosk-model-small-de-0.15").apply { mkdirs(); writeFakeModelFiles(this) }

        val createdModels = mutableMapOf<String, FakeModelHandle>()
        val service = VoskBuiltinService(
            modelManager = manager,
            settings = { SpeechSettings(voskBuiltinModel = "vosk-model-small-en-us-0.15") },
            modelFactory = { dir -> FakeModelHandle().also { createdModels[dir.name] = it } }
        )

        // Open live session using model 1
        val session1 = service.openSession(LanguageCode("en")).get()
        assertNotNull(session1)
        assertTrue(service.isModelInUse("vosk-model-small-en-us-0.15"))

        // Preload model 2
        service.preloadModel("vosk-model-small-de-0.15")
        assertEquals(2, service.cachedModelCount)

        // Switching selected model to model 3:
        // Model 2 is not in use and not selected -> unloaded!
        // Model 1 is in use by session1 -> NOT unloaded!
        service.onSelectedModelChanged("vosk-model-small-fr-0.22")

        assertTrue(service.isModelCached("vosk-model-small-en-us-0.15"), "In-use model must NOT be unloaded")
        assertFalse(service.isModelCached("vosk-model-small-de-0.15"), "Unselected idle model must be unloaded")
        assertTrue(createdModels["vosk-model-small-de-0.15"]?.closed?.get() == true)

        // Close session 1
        session1.finish()
        assertFalse(service.isModelInUse("vosk-model-small-en-us-0.15"))

        // Now switching model unloads model 1
        service.onSelectedModelChanged("vosk-model-small-fr-0.22")
        assertFalse(service.isModelCached("vosk-model-small-en-us-0.15"))
    }

    @Test
    fun `native library loading failures report platform unsupported message`() = runBlocking {
        val manager = VoskModelManager({ tempDir })
        val m1 = File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs(); writeFakeModelFiles(this) }

        val service = VoskBuiltinService(
            modelManager = manager,
            settings = { SpeechSettings(voskBuiltinModel = "vosk-model-small-en-us-0.15") },
            modelFactory = { throw UnsatisfiedLinkError("Cannot load libvosk.so") }
        )

        val err = service.openSession(LanguageCode("en")).getError()
        assertNotNull(err)
        assertTrue(err.message.contains("Built-in Vosk is not supported on this platform"))

        val damagedService = VoskBuiltinService(
            modelManager = manager,
            settings = { SpeechSettings(voskBuiltinModel = "vosk-model-small-en-us-0.15") },
            modelFactory = { throw RuntimeException("Failed to read model files") }
        )
        val damagedErr = damagedService.openSession(LanguageCode("en")).getError()
        assertNotNull(damagedErr)
        assertTrue(damagedErr.message.contains("the model files look damaged - delete and download it again"))
    }

    @Test
    fun `finish timeout releases recognizer and shuts down worker`() = runBlocking {
        val recognizer = FakeRecognizerHandle()
        val session = VoskBuiltinSession(recognizer)

        // Queue audio and cancel finish with a short timeout
        session.sendAudio(ByteArray(1600))
        val job = launch {
            kotlinx.coroutines.withTimeout(1) {
                // Delay artificially to simulate finish timing out
                kotlinx.coroutines.delay(100)
                session.finish()
            }
        }
        job.join()
        session.finish()

        assertTrue(recognizer.closed.get(), "Recognizer must be closed after finish cleanup even if cancelled")
    }

    @Test
    fun `parseVoskJson extracts word confidence from partial_result and result`() {
        val partialRaw = """{"partial":"hello maybe","partial_result":[{"conf":0.95,"start":0.1,"end":0.4,"word":"hello"},{"conf":0.45,"start":0.5,"end":0.9,"word":"maybe"}]}"""
        val partial = parseVoskJson(partialRaw)
        assertTrue(partial is VoskParsedResult.Partial)
        assertEquals(2, partial.words.size)
        assertEquals(0.95, partial.words[0].confidence)
        assertEquals(0.45, partial.words[1].confidence)

        val finalRaw = """{"text":"hello maybe","result":[{"conf":0.91,"start":0.1,"end":0.4,"word":"hello"},{"conf":0.52,"start":0.5,"end":0.9,"word":"maybe"}]}"""
        val finalRes = parseVoskJson(finalRaw)
        assertTrue(finalRes is VoskParsedResult.Final)
        assertEquals(2, finalRes.words.size)
        assertEquals(0.91, finalRes.words[0].confidence)
        assertEquals(0.52, finalRes.words[1].confidence)
    }
}

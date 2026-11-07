package com.github.ahatem.qtranslate.core.ocr.workspace

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRegionKind
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.api.ocr.StructuredOCR
import com.github.ahatem.qtranslate.api.ocr.StructuredOCRResponse
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.ocr.OcrExtractionUseCase
import com.github.ahatem.qtranslate.core.main.mvi.RecordingOcrService
import com.github.ahatem.qtranslate.core.main.mvi.Silent353Factory
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OcrWorkspaceStoreTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun closeScopes() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `opening an image selects the configured provider and recognizes it`() = runTest {
        val ocr = RecordingOcrService()
        ocr.responder = { Ok(OCRResponse("workspace text")) }
        val fixture = fixture(this, ocr)
        assertEquals(listOf("ocr", "second"), fixture.store.state.value.providers.map { it.id })
        assertEquals("ocr", fixture.store.state.value.providerId)

        fixture.store.openImage(fixture.image, "capture", autoRun = true)
        assertTrue(fixture.store.state.value.isRecognizing, "opening with autoRun must start recognition")
        advanceUntilIdle()

        val state = fixture.store.state.value
        assertNull(state.error)
        assertEquals(1, ocr.requests.size)
        assertEquals("workspace text", state.recognizedText)
        assertFalse(state.isRecognizing)
        assertEquals("ocr", state.providerId)
        assertEquals(LanguageCode.ENGLISH, state.language)
    }

    @Test
    fun `a blank recognition is a typed no-text state and leaves no busy spinner`() = runTest {
        val ocr = RecordingOcrService()
        ocr.responder = { Ok(OCRResponse("")) }
        val fixture = fixture(this, ocr)

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()

        val state = fixture.store.state.value
        assertEquals("", state.recognizedText)
        assertEquals(OcrWorkspaceError.NoText, state.error)
        assertFalse(state.isRecognizing)
    }

    @Test
    fun `a provider failure is recoverable and a retry can succeed`() = runTest {
        val ocr = RecordingOcrService()
        ocr.responder = { Err(ServiceError.InvalidResponseError("provider detail")) }
        val fixture = fixture(this, ocr)

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()
        assertTrue(fixture.store.state.value.error is OcrWorkspaceError.Failed)
        assertFalse(fixture.store.state.value.isRecognizing)

        ocr.responder = { Ok(OCRResponse("after retry")) }
        fixture.store.runOcr()
        advanceUntilIdle()
        assertEquals("after retry", fixture.store.state.value.recognizedText)
        assertNull(fixture.store.state.value.error)
    }

    @Test
    fun `edits survive a provider change and are replaced only by an explicit re-run`() = runTest {
        val ocr = RecordingOcrService()
        val fixture = fixture(this, ocr)
        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()
        fixture.store.updateText("OpenAI")

        assertEquals("OpenAI", fixture.store.state.value.recognizedText)
        assertTrue(fixture.store.state.value.isEdited)

        fixture.store.selectProvider("second")
        assertEquals("OpenAI", fixture.store.state.value.recognizedText, "switching provider must not erase edits")
        assertEquals(
            listOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            fixture.store.state.value.languages.sortedBy { it.tag },
            "the language picker follows the selected provider",
        )

        fixture.store.runOcr()
        advanceUntilIdle()
        assertEquals("recognized text", fixture.store.state.value.recognizedText)
        assertFalse(fixture.store.state.value.isEdited)
    }

    @Test
    fun `re-run supersedes the older request`() = runTest {
        val ocr = RecordingOcrService()
        var call = 0
        ocr.responder = {
            call++
            if (call == 1) {
                kotlinx.coroutines.delay(10_000)
                Ok(OCRResponse("stale result"))
            } else {
                Ok(OCRResponse("current result"))
            }
        }
        val fixture = fixture(this, ocr)

        fixture.store.openImage(fixture.image, "capture")
        runCurrent()
        fixture.store.runOcr()
        advanceUntilIdle()

        assertEquals("current result", fixture.store.state.value.recognizedText)
        assertFalse(fixture.store.state.value.isRecognizing)
    }

    @Test
    fun `closing cancels recognition and no late result can touch state`() = runTest {
        val ocr = RecordingOcrService()
        ocr.responder = {
            kotlinx.coroutines.delay(10_000)
            Ok(OCRResponse("late result"))
        }
        val fixture = fixture(this, ocr)

        fixture.store.openImage(fixture.image, "capture")
        runCurrent()
        assertTrue(fixture.store.state.value.isRecognizing)

        fixture.store.close()
        advanceUntilIdle()

        val afterClose = fixture.store.state.value
        assertFalse(afterClose.isRecognizing)
        assertEquals("", afterClose.recognizedText)
    }

    @Test
    fun `loading a dynamic provider language list does not cancel the first auto-run`() = runTest {
        val dynamic = DynamicOcrService()
        val fixture = fixture(this, dynamic)

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()

        assertEquals("resolved by the provider", fixture.store.state.value.recognizedText)
        assertFalse(fixture.store.state.value.isRecognizing)
        assertEquals(LanguageCode.ENGLISH, fixture.store.state.value.language)
    }

    @Test
    fun `geometry is retained when the provider reports it`() = runTest {
        val region = OCRTextRegion("recognized", 0, 0, 40, 12, OCRRegionKind.LINE)
        val delegate = RecordingOcrService()
        delegate.responder = { Ok(OCRResponse("recognized")) }
        val structured = StructuredRecordingOcr(delegate, region)
        val fixture = fixture(this, structured)

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()

        assertEquals("recognized", fixture.store.state.value.recognizedText)
        assertEquals(listOf(region), fixture.store.state.value.regions)
    }

    @Test
    fun `a provider without geometry still produces usable text`() = runTest {
        val ocr = RecordingOcrService()
        ocr.responder = { Ok(OCRResponse("plain text only")) }
        val fixture = fixture(this, ocr)

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()

        assertEquals("plain text only", fixture.store.state.value.recognizedText)
        assertTrue(fixture.store.state.value.regions.isEmpty())
    }

    @Test
    fun `openWithResult adopts capture output instead of recognizing again`() = runTest {
        val ocr = RecordingOcrService()
        val fixture = fixture(this, ocr)

        val region = OCRTextRegion("already recognized", 1, 2, 30, 12)
        fixture.store.openWithResult(
            image = fixture.image,
            label = "capture",
            text = "already recognized",
            regions = listOf(region),
        )
        advanceUntilIdle()

        assertEquals("already recognized", fixture.store.state.value.recognizedText)
        assertEquals(listOf(region), fixture.store.state.value.regions)
        assertEquals(0, ocr.requests.size)
    }

    @Test
    fun `initial adoption uses the saved explicit recognition language`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        val fixture = fixture(
            this,
            ocr,
            OcrSettings(recognitionLanguage = LanguageCode.JAPANESE.tag),
        )

        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.language)
    }

    @Test
    fun `blank recognition setting follows the translation source`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        val fixture = fixture(
            this,
            ocr,
            OcrSettings(recognitionLanguage = ""),
            translationSource = LanguageCode.JAPANESE,
        )

        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.language)
    }

    @Test
    fun `auto recognition setting stays auto for a provider that resolves it internally`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        val fixture = fixture(
            this,
            ocr,
            OcrSettings(recognitionLanguage = LanguageCode.AUTO.tag),
        )

        assertEquals(LanguageCode.AUTO, fixture.store.state.value.language)
    }

    @Test
    fun `auto recognition setting selects auto when the provider offers it`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.AUTO, LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        val fixture = fixture(
            this,
            ocr,
            OcrSettings(recognitionLanguage = LanguageCode.AUTO.tag),
        )

        assertEquals(LanguageCode.AUTO, fixture.store.state.value.language)
    }

    @Test
    fun `an unsupported saved recognition language falls back safely`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.AUTO, LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        val fixture = fixture(
            this,
            ocr,
            OcrSettings(recognitionLanguage = "de"),
        )

        assertEquals(LanguageCode.AUTO, fixture.store.state.value.language)
    }

    @Test
    fun `a dynamic language list adopts the saved language without changing the first request`() = runTest {
        val dynamic = DynamicOcrService(setOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE))
        val fixture = fixture(
            this,
            dynamic,
            OcrSettings(recognitionLanguage = LanguageCode.JAPANESE.tag),
        )

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()

        assertEquals(LanguageCode.AUTO, dynamic.requests.single().language)
        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.language)
        assertEquals("resolved by the provider", fixture.store.state.value.recognizedText)
    }

    @Test
    fun `an explicit language choice survives the provider list temporarily emptying`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        val fixture = fixture(this, ocr)
        val original = fixture.services.value
        fixture.store.selectLanguage(LanguageCode.JAPANESE)

        fixture.services.value = emptyMap()
        advanceUntilIdle()
        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.language)

        fixture.services.value = original
        advanceUntilIdle()
        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.language)
    }

    @Test
    fun `detected OCR language is retained independently of the next-run dropdown`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.AUTO, LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        ocr.responder = {
            Ok(OCRResponse("こんにちは", detectedLanguage = LanguageCode.JAPANESE))
        }
        val fixture = fixture(
            this,
            ocr,
            OcrSettings(recognitionLanguage = LanguageCode.AUTO.tag),
        )

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()

        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.recognizedLanguage)
        assertEquals(LanguageCode.AUTO, fixture.store.state.value.language)

        fixture.store.selectLanguage(LanguageCode.ENGLISH)
        assertEquals(LanguageCode.ENGLISH, fixture.store.state.value.language)
        assertEquals(
            LanguageCode.JAPANESE,
            fixture.store.state.value.recognizedLanguage,
            "changing the next OCR language must not rewrite the current result's provenance",
        )

        fixture.store.updateText("こんにちは!")
        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.recognizedLanguage)
    }

    @Test
    fun `an explicit request language is the provenance when the provider detects nothing`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        val fixture = fixture(
            this,
            ocr,
            OcrSettings(recognitionLanguage = LanguageCode.JAPANESE.tag),
        )

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()

        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.recognizedLanguage)
    }

    @Test
    fun `auto recognition with no detected language leaves provenance unknown`() = runTest {
        val ocr = RecordingOcrService(
            supportedLanguages = SupportedLanguages.Specific(
                setOf(LanguageCode.AUTO, LanguageCode.ENGLISH, LanguageCode.JAPANESE),
            ),
        )
        val fixture = fixture(
            this,
            ocr,
            OcrSettings(recognitionLanguage = LanguageCode.AUTO.tag),
        )

        fixture.store.openImage(fixture.image, "capture")
        advanceUntilIdle()

        assertNull(fixture.store.state.value.recognizedLanguage)
    }

    @Test
    fun `adopted capture text keeps the language that belongs to it`() = runTest {
        val fixture = fixture(this, RecordingOcrService())

        fixture.store.openWithResult(
            image = fixture.image,
            label = "capture",
            text = "こんにちは",
            regions = emptyList(),
            recognizedLanguage = LanguageCode.JAPANESE,
        )
        fixture.store.selectLanguage(LanguageCode.ENGLISH)

        assertEquals(LanguageCode.JAPANESE, fixture.store.state.value.recognizedLanguage)
    }

    @Test
    fun `adopted capture text never borrows the next-run language when provenance is unknown`() = runTest {
        val fixture = fixture(this, RecordingOcrService())

        fixture.store.openWithResult(
            image = fixture.image,
            label = "capture",
            text = "unknown language",
            regions = emptyList(),
            recognizedLanguage = null,
        )

        assertNull(
            fixture.store.state.value.recognizedLanguage,
            "the next-run dropdown is not evidence of the adopted text's language",
        )
    }

    @Test
    fun `a stale request cannot publish after a newer input owns the workspace`() = runBlocking {
        val imageA = ImageData(byteArrayOf(1), "png", 100, 50)
        val imageB = ImageData(byteArrayOf(2), "png", 10, 10)
        val firstAtPublication = CountDownLatch(1)
        val releaseFirst = java.util.concurrent.Semaphore(0)
        val firstAttemptFinished = CountDownLatch(1)
        val firstToken = AtomicLong(-1)
        val releaseSecond = java.util.concurrent.Semaphore(0)

        val ocr = RecordingOcrService()
        ocr.responder = { request ->
            if (request.image.width == imageA.width) {
                Ok(OCRResponse("STALE_A"))
            } else {
                releaseSecond.acquireUninterruptibly()
                Ok(OCRResponse("CURRENT_B"))
            }
        }
        val fixture = threadFixture(ocr)
        fixture.store.beforePublication = { token ->
            if (firstToken.compareAndSet(-1L, token)) {
                firstAtPublication.countDown()
                releaseFirst.acquireUninterruptibly()
            }
        }
        fixture.store.afterPublication = { token ->
            if (token == firstToken.get()) firstAttemptFinished.countDown()
        }
        fixture.store.openImage(imageA, "A")
        assertTrue(firstAtPublication.await(5, TimeUnit.SECONDS), "request A never reached publication")

        // B claims the workspace synchronously. Its recognition is deliberately still running.
        fixture.store.openImage(imageB, "B")
        assertEquals(imageB, fixture.store.state.value.image)
        assertTrue(fixture.store.state.value.isRecognizing)
        assertEquals("", fixture.store.state.value.recognizedText)

        releaseFirst.release()
        assertTrue(firstAttemptFinished.await(5, TimeUnit.SECONDS), "request A never finished publishing")

        val whileBBlocked = fixture.store.state.value
        assertEquals(imageB, whileBBlocked.image, "stale A must not replace B's image")
        assertEquals("", whileBBlocked.recognizedText, "stale A must not publish text")
        assertTrue(whileBBlocked.isRecognizing, "stale A must not clear B's loading state")
        assertNull(whileBBlocked.error, "stale A must not publish an error")

        releaseSecond.release()
        val finished = withTimeout(5_000) {
            fixture.store.state.first { it.image == imageB && !it.isRecognizing }
        }
        assertEquals("CURRENT_B", finished.recognizedText)
    }

    @Test
    fun `a stale image input cannot replace the latest one`() = runBlocking {
        val fixture = threadFixture(RecordingOcrService())
        val imageA = ImageData(byteArrayOf(1), "png", 100, 50)
        val imageB = ImageData(byteArrayOf(2), "png", 10, 10)

        val tokenA = fixture.store.beginImageInput()!!
        val tokenB = fixture.store.beginImageInput()!!
        assertFalse(fixture.store.completeImageInput(tokenA, imageA, "A", autoRun = true))
        assertTrue(fixture.store.completeImageInput(tokenB, imageB, "B", autoRun = false))

        val state = fixture.store.state.value
        assertEquals(imageB, state.image)
        assertEquals("B", state.imageLabel)
        assertFalse(state.isLoadingImage)
    }

    @Test
    fun `a second input claimed before the first materializes keeps the workspace loading`() = runBlocking {
        val fixture = threadFixture(RecordingOcrService())
        val imageA = ImageData(byteArrayOf(1), "png", 100, 50)
        val imageB = ImageData(byteArrayOf(2), "png", 10, 10)

        val tokenA = fixture.store.beginImageInput()!!
        assertTrue(fixture.store.state.value.isLoadingImage)
        val tokenB = fixture.store.beginImageInput()!!
        assertTrue(fixture.store.state.value.isLoadingImage, "the newer input is still materializing")
        assertFalse(fixture.store.completeImageInput(tokenA, imageA, "A", autoRun = false))
        assertTrue(fixture.store.state.value.isLoadingImage, "stale A must not end B's materialization")

        assertTrue(fixture.store.completeImageInput(tokenB, imageB, "B", autoRun = false))
        assertFalse(fixture.store.state.value.isLoadingImage)
        assertEquals(imageB, fixture.store.state.value.image)
    }

    @Test
    fun `a failed materialization restores the last usable workspace content`() = runBlocking {
        val fixture = threadFixture(RecordingOcrService())
        val imageA = ImageData(byteArrayOf(1), "png", 100, 50)

        assertTrue(fixture.store.completeImageInput(
            token = fixture.store.beginImageInput()!!,
            image = imageA,
            label = "A",
            autoRun = false,
            initialText = "recognized A",
        ))
        fixture.store.updateText("edited A")
        val tokenB = fixture.store.beginImageInput()!!
        assertTrue(fixture.store.failImageInput(tokenB, OcrWorkspaceError.ImageOpenFailed))

        val state = fixture.store.state.value
        assertEquals(imageA, state.image)
        assertEquals("edited A", state.recognizedText)
        assertEquals(OcrWorkspaceError.ImageOpenFailed, state.error)
        assertFalse(state.isLoadingImage)
    }

    @Test
    fun `a stale input failure does not surface over the current image`() = runBlocking {
        val fixture = threadFixture(RecordingOcrService())
        val imageB = ImageData(byteArrayOf(2), "png", 10, 10)

        val tokenA = fixture.store.beginImageInput()!!
        val tokenB = fixture.store.beginImageInput()!!
        assertFalse(fixture.store.failImageInput(tokenA, OcrWorkspaceError.ImageOpenFailed))
        assertTrue(fixture.store.completeImageInput(tokenB, imageB, "B", autoRun = false))

        assertNull(fixture.store.state.value.error)
        assertEquals(imageB, fixture.store.state.value.image)
    }

    @Test
    fun `edits during materialization cannot redefine the last valid image fallback`() = runBlocking {
        val fixture = threadFixture(RecordingOcrService())
        val imageA = ImageData(byteArrayOf(1), "png", 100, 50)
        val imageB = ImageData(byteArrayOf(2), "png", 20, 10)
        val regions = listOf(OCRTextRegion("recognized A", 0, 0, 40, 12))
        fixture.store.openWithResult(imageA, "A", "recognized A", regions, LanguageCode.JAPANESE)
        fixture.store.updateText("edited A")
        val tokenB = fixture.store.beginImageInput()!!
        fixture.store.updateText("temporary loading edit")
        fixture.store.failImageInput(tokenB, OcrWorkspaceError.ImageOpenFailed)
        val restored = fixture.store.state.value
        assertEquals(imageA, restored.image)
        assertEquals("edited A", restored.recognizedText)
        assertEquals(regions, restored.regions)
        assertEquals(LanguageCode.JAPANESE, restored.recognizedLanguage)
        assertTrue(restored.isEdited)
        assertFalse(restored.isLoadingImage)

        val retry = fixture.store.beginImageInput()!!
        fixture.store.updateText("another loading edit")
        assertTrue(fixture.store.completeImageInput(retry, imageB, "B", false, initialText = "recognized B"))
        assertEquals(imageB, fixture.store.state.value.image)
        assertEquals("recognized B", fixture.store.state.value.recognizedText)
        assertTrue(fixture.store.state.value.regions.isEmpty())
    }

    private class Fixture(
        val image: ImageData,
        val store: OcrWorkspaceStore,
        val services: MutableStateFlow<Map<String, Service>>,
    )

    private fun fixture(
        scope: TestScope,
        ocr: OCR,
        ocrSettings: OcrSettings = OcrSettings(recognitionLanguage = "en"),
        translationSource: LanguageCode = LanguageCode.ENGLISH,
    ): Fixture {
        val runtime = CoroutineScope(
            SupervisorJob() + scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!
        )
        scopes += runtime
        return buildFixture(runtime, ocr, ocrSettings, translationSource)
    }

    /** Real-thread fixture for race tests: blocking gates must not hold the test scheduler. */
    private fun threadFixture(
        ocr: OCR,
        ocrSettings: OcrSettings = OcrSettings(recognitionLanguage = "en"),
        translationSource: LanguageCode = LanguageCode.ENGLISH,
    ): Fixture {
        val runtime = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        scopes += runtime
        return buildFixture(runtime, ocr, ocrSettings, translationSource)
    }

    private fun buildFixture(
        runtime: CoroutineScope,
        ocr: OCR,
        ocrSettings: OcrSettings,
        translationSource: LanguageCode,
    ): Fixture {
        val second = RecordingOcrService(
            key = "second",
            name = "Second OCR",
            supportedLanguages = SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH, LanguageCode.JAPANESE)),
        )
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.OCR to "ocr"),
        )
        val configuration = MutableStateFlow(
            Configuration.DEFAULT.copy(
                servicePresets = listOf(preset),
                activeServicePresetId = preset.id,
                ocr = ocrSettings,
            )
        )
        val services = mapOf<String, Service>("ocr" to ocr, "second" to second)
        val serviceFlow = MutableStateFlow(services)
        val manager = ActiveServiceManager(serviceFlow, configuration)
        val catalog = OcrServiceCatalog(
            activeServices = serviceFlow,
            configuration = configuration,
            scope = runtime,
            logger = Silent353Factory.getLogger("catalog"),
        )
        val store = OcrWorkspaceStore(
            scope = runtime,
            extraction = OcrExtractionUseCase(manager, Silent353Factory),
            catalog = catalog,
            configuration = { configuration.value },
            translationSource = { translationSource },
        )
        return Fixture(
            image = ImageData(byteArrayOf(1, 2, 3), "png", 100, 50),
            store = store,
            services = serviceFlow,
        )
    }

    /** Dynamic languages arrive after the workspace has already started its first request. */
    private class DynamicOcrService(
        private val languages: Set<LanguageCode> = setOf(LanguageCode.ENGLISH),
    ) : OCR {
        override val key: String = "ocr"
        override val name: String = "Dynamic OCR"
        override val version: String = "test"
        override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic
        val requests = mutableListOf<OCRRequest>()

        override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
            Ok(languages)

        override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> {
            requests += request
            kotlinx.coroutines.delay(1_000)
            return Ok(OCRResponse("resolved by the provider"))
        }
    }

    /** A structured provider that reuses the recording service's text and request log. */
    private class StructuredRecordingOcr(
        private val delegate: RecordingOcrService,
        private val region: OCRTextRegion,
    ) : StructuredOCR {
        override val key: String get() = delegate.key
        override val name: String get() = delegate.name
        override val version: String get() = delegate.version
        override val supportedLanguages: SupportedLanguages get() = delegate.supportedLanguages

        override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
            delegate.extractText(request)

        override suspend fun extractStructuredText(
            request: OCRRequest,
        ): Result<StructuredOCRResponse, ServiceError> =
            delegate.extractText(request).map { response ->
                StructuredOCRResponse(text = response.text, regions = listOf(region))
            }
    }
}

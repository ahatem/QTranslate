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
import com.github.ahatem.qtranslate.core.main.domain.usecase.OcrAndTranslateUseCase
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    private class Fixture(
        val image: ImageData,
        val store: OcrWorkspaceStore,
    )

    private fun fixture(scope: TestScope, ocr: OCR): Fixture {
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
                ocr = OcrSettings(recognitionLanguage = "en"),
            )
        )
        val services = mapOf<String, Service>("ocr" to ocr, "second" to second)
        val manager = ActiveServiceManager(MutableStateFlow(services), configuration)
        val runtime = CoroutineScope(
            SupervisorJob() + scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!
        )
        scopes += runtime
        val catalog = OcrServiceCatalog(
            activeServices = MutableStateFlow(services),
            configuration = configuration,
            scope = runtime,
            logger = Silent353Factory.getLogger("catalog"),
        )
        val store = OcrWorkspaceStore(
            scope = runtime,
            extraction = OcrAndTranslateUseCase(manager, Silent353Factory),
            catalog = catalog,
            configuration = { configuration.value },
            translationSource = { LanguageCode.ENGLISH },
        )
        return Fixture(
            image = ImageData(byteArrayOf(1, 2, 3), "png", 100, 50),
            store = store,
        )
    }

    /** Dynamic languages arrive after the workspace has already started its first request. */
    private class DynamicOcrService : OCR {
        override val key: String = "ocr"
        override val name: String = "Dynamic OCR"
        override val version: String = "test"
        override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

        override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
            Ok(setOf(LanguageCode.ENGLISH))

        override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> {
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

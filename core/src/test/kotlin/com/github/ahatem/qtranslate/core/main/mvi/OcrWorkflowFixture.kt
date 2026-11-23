package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope

/** Test OCR that records requests and can be made to answer with any text or failure. */
internal class RecordingOcrService(
    override val key: String = "ocr",
    override val name: String = "Recording OCR",
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH)),
) : OCR {
    override val version = "test"

    val requests = mutableListOf<OCRRequest>()
    var responder: suspend (OCRRequest) -> Result<OCRResponse, ServiceError> = {
        Ok(OCRResponse("recognized text"))
    }

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> {
        requests += request
        return responder(request)
    }
}

/** A [MainStoreFixture] with a recording translator and OCR provider. */
internal class OcrWorkflowFixture(
    testScope: TestScope,
    ocrSettings: OcrSettings = OcrSettings(),
    autoCopy: AutoCopyTranslation = AutoCopyTranslation.OFF,
    dispatcher: CoroutineDispatcher? = null,
    collectEvents: Boolean = true,
) {
    val translator = RecordingTranslator()
    val ocr = RecordingOcrService()
    private val main = MainStoreFixture(
        services = mapOf(translator.key to translator, ocr.key to ocr),
        configuration = MainStoreFixture.configuration(
            mapOf(ServiceRole.TRANSLATOR to translator.key, ServiceRole.OCR to ocr.key)
        ) { copy(autoCopyTranslation = autoCopy, ocr = ocrSettings) },
        testScope = testScope,
        dispatcher = dispatcher,
        collectEvents = collectEvents,
    )

    val settings get() = main.settings
    val events get() = main.events
    val store get() = main.store

    suspend fun copiedAfter(block: () -> Unit): String = main.copiedAfter(block)

    suspend fun awaitEvent(predicate: (MainEvent) -> Boolean): MainEvent = main.awaitEvent(predicate)

    fun image(): ImageData = ImageData(byteArrayOf(1, 2, 3), "png", 8, 8)

    fun cleanUp() = main.cleanUp()
}
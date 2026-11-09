package com.github.ahatem.qtranslate.core.workspace.lookup

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.dictionary.*
import com.github.ahatem.qtranslate.api.imagesearch.*
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.*
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LookupOutcomesTest {
    private val word = DictionaryLookupRequest("term", LanguageCode.ENGLISH, null)
    private val image = ImageLookupRequest("term", LanguageCode.ENGLISH)

    @Test fun `dictionary empty response is distinct from provider failure and success`() = runTest {
        val entries = listOf(DictionaryEntry("term", "", emptyList()))
        for (response in listOf(Ok(DictionaryResponse(entries)), Ok(DictionaryResponse(emptyList())),
            Err(ServiceError.NetworkError("synthetic failure")))) {
            val useCase = LookupWordUseCase(manager(FakeDictionary { response }), silent)
            var started = false
            val result = useCase(word) { started = true }
            assertTrue(started)
            response.fold(success = { payload ->
                if (payload.entries.isEmpty()) {
                    assertEquals(StatusCode.DictionaryNotFound("term"), result.status)
                    assertNull(result.failed)
                } else { assertEquals(entries, result.entries); assertEquals(false, result.failed) }
            }, failure = { assertTrue(result.status is StatusCode.DictionaryFailed); assertEquals(true, result.failed) })
        }
    }

    @Test fun `image response retains attribution and distinguishes empty response from failure`() = runTest {
        val images = listOf(ImageResult("thumbnail", "full", attribution = "synthetic credit", license = "CC0"))
        for (response in listOf(Ok(ImageSearchResponse(images)), Ok(ImageSearchResponse(emptyList())),
            Err(ServiceError.NetworkError("synthetic failure")))) {
            val useCase = SearchImagesUseCase(manager(FakeImages { response }), silent)
            var started = false
            val result = useCase(image) { started = true }
            assertTrue(started)
            response.fold(success = { payload ->
                if (payload.results.isEmpty()) {
                    assertEquals(StatusCode.ImagesNotFound("term"), result.status)
                    assertNull(result.failed)
                } else { assertEquals(images, result.results); assertEquals(false, result.failed) }
            }, failure = { assertTrue(result.status is StatusCode.ImageSearchFailed); assertEquals(true, result.failed) })
        }
    }

    @Test fun `blank requests retain results while missing services return failure without starting`() = runTest {
        val dictionary = LookupWordUseCase(manager(), silent)
        val images = SearchImagesUseCase(manager(), silent)
        val neverStart: suspend () -> Unit = { error("request must not start") }
        assertNull(dictionary(word.copy(word = " "), neverStart).entries)
        assertNull(images(image.copy(term = " "), neverStart).results)
        val dictionaryFailure = dictionary(word, neverStart)
        assertEquals(StatusCode.NoDictionaryServiceActive, dictionaryFailure.status)
        assertEquals(emptyList(), dictionaryFailure.entries)
        assertEquals(true, dictionaryFailure.failed)
        val imageFailure = images(image, neverStart)
        assertEquals(StatusCode.NoImageSearchServiceActive, imageFailure.status)
        assertEquals(emptyList(), imageFailure.results)
        assertEquals(true, imageFailure.failed)
    }

    @Test fun `provider timeout returns typed failure and cancellation propagates for both lookups`() = runTest {
        val dictionary = LookupWordUseCase(manager(FakeDictionary { awaitCancellation() }), silent)
        assertEquals(StatusCode.DictionaryTimeout, dictionary(word) {}.status)
        val images = SearchImagesUseCase(manager(FakeImages { awaitCancellation() }), silent)
        assertEquals(StatusCode.ImageSearchTimeout, images(image) {}.status)
        val cancelledDictionary = LookupWordUseCase(manager(FakeDictionary { throw CancellationException() }), silent)
        assertFailsWith<CancellationException> { cancelledDictionary(word) {} }
        val cancelledImages = SearchImagesUseCase(manager(FakeImages { throw CancellationException() }), silent)
        assertFailsWith<CancellationException> { cancelledImages(image) {} }
    }

    @Test fun `unexpected provider exceptions return failure outcomes for both lookups`() = runTest {
        val dictionary = LookupWordUseCase(manager(FakeDictionary { error("synthetic exception") }), silent)
        assertTrue(dictionary(word) {}.status is StatusCode.DictionaryFailed)
        val images = SearchImagesUseCase(manager(FakeImages { error("synthetic exception") }), silent)
        assertTrue(images(image) {}.status is StatusCode.ImageSearchFailed)
    }

    private fun manager(vararg services: Service) = ActiveServiceManager(
        MutableStateFlow(services.associateBy { it.key }), MutableStateFlow(Configuration.DEFAULT))

    private class FakeDictionary(val response: suspend () -> Result<DictionaryResponse, ServiceError>) : Dictionary {
        override val key = "dictionary"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun lookup(request: DictionaryRequest) = response()
    }

    private class FakeImages(val response: suspend () -> Result<ImageSearchResponse, ServiceError>) : ImageSearch {
        override val key = "images"
        override val name = key
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun searchImages(request: ImageSearchRequest) = response()
    }

    private val silent = object : LoggerFactory {
        override fun getLogger(name: String) = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}

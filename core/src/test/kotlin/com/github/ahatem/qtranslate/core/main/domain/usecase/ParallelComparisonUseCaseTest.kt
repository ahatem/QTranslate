package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ParallelComparisonUseCaseTest {

    @Test
    fun `providers start concurrently and results keep configured order`() = runTest {
        val first = ControlledTranslator("first")
        val second = ControlledTranslator("second")
        val useCase = useCase(this, listOf(first, second))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.start(request(listOf("second", "first", "second")), 1) { state = it }
        runCurrent()

        assertTrue(first.started.isCompleted)
        assertTrue(second.started.isCompleted)
        second.release.complete(Unit)
        first.release.complete(Unit)
        runCurrent()

        assertEquals(listOf("second", "first"), state.map { it.serviceId })
        assertEquals(listOf(ComparisonStatus.SUCCESS, ComparisonStatus.SUCCESS), state.map { it.status })
    }

    @Test
    fun `one provider failure leaves its sibling successful`() = runTest {
        val failed = ControlledTranslator("failed", failure = true)
        val successful = ControlledTranslator("successful")
        val useCase = useCase(this, listOf(failed, successful))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.start(request(listOf("failed", "successful")), 1) { state = it }
        runCurrent()
        failed.release.complete(Unit)
        successful.release.complete(Unit)
        runCurrent()

        assertEquals(ComparisonStatus.FAILURE, state[0].status)
        assertEquals(ComparisonStatus.SUCCESS, state[1].status)
    }

    @Test
    fun `stale non cooperative completion cannot publish after invalidation`() = runTest {
        val stale = ControlledTranslator("stale", ignoreCancellation = true)
        val useCase = useCase(this, listOf(stale))
        var state = emptyList<ComparisonTranslationResult>()

        useCase.start(request(listOf("stale")), 1) { state = it }
        runCurrent()
        useCase.invalidate()
        stale.release.complete(Unit)
        runCurrent()

        assertEquals(ComparisonStatus.LOADING, state.single().status)
    }

    private fun request(ids: List<String>) = ParallelComparisonRequest(
        text = "hello",
        sourceLanguage = LanguageCode.ENGLISH,
        targetLanguage = LanguageCode.ARABIC,
        primaryTranslatorId = "primary",
        translatorIds = ids
    )

    private fun useCase(
        scope: CoroutineScope,
        translators: List<ControlledTranslator>,
    ): ParallelComparisonUseCase {
        val services = translators.associateBy { it.key } + ("primary" to ControlledTranslator("primary"))
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(com.github.ahatem.qtranslate.api.plugin.ServiceRole.TRANSLATOR to "primary")
        )
        val config = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id
        )
        return ParallelComparisonUseCase(
            scope = scope,
            activeServiceManager = ActiveServiceManager(
                MutableStateFlow(services),
                MutableStateFlow(config)
            ),
            loggerFactory = TestLoggerFactory
        )
    }

    private class ControlledTranslator(
        override val key: String,
        private val failure: Boolean = false,
        private val ignoreCancellation: Boolean = false
    ) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun translate(request: TranslationRequest): com.github.michaelbull.result.Result<TranslationResponse, ServiceError> {
            started.complete(Unit)
            if (ignoreCancellation) {
                withContext(NonCancellable) { release.await() }
            } else {
                release.await()
            }
            return if (failure) Err(ServiceError.NetworkError("failed")) else Ok(TranslationResponse(key))
        }
    }

    private object TestLoggerFactory : LoggerFactory {
        private val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }

        override fun getLogger(name: String): Logger = logger
    }
}

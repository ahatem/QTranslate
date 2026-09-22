package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.util.shortSummary
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicLong

data class ParallelComparisonRequest(
    val text: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val primaryTranslatorId: String,
    val translatorIds: List<String>
)

/** Runs configured comparison translators independently of the canonical primary translation. */
class ParallelComparisonUseCase(
    private val scope: CoroutineScope,
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("ParallelComparisonUseCase")
    private val generations = AtomicLong()
    private val ownershipLock = Any()
    private var latestGeneration = Long.MIN_VALUE
    private var invalidatedThroughGeneration = Long.MIN_VALUE
    private var comparisonJob: Job? = null

    /** Claims a newer translation generation and cancels the previous comparison owner. */
    fun begin(generation: Long) {
        synchronized(ownershipLock) {
            if (generation <= latestGeneration) return
            latestGeneration = generation
            comparisonJob?.cancel(CancellationException("Comparison superseded"))
            comparisonJob = null
            generations.set(generation)
        }
    }

    fun invalidate() {
        synchronized(ownershipLock) {
            invalidateLocked(latestGeneration)
        }
    }

    /** Permanently invalidates [generation] unless a newer generation already owns the executor. */
    fun invalidate(generation: Long) {
        synchronized(ownershipLock) {
            invalidateLocked(generation)
        }
    }

    private fun invalidateLocked(generation: Long) {
        if (generation < latestGeneration) return
        latestGeneration = generation
        invalidatedThroughGeneration = maxOf(invalidatedThroughGeneration, generation)
        generations.set(generation)
        comparisonJob?.cancel(CancellationException("Comparison invalidated"))
        comparisonJob = null
    }

    fun start(
        request: ParallelComparisonRequest,
        generation: Long,
        updateState: (List<ComparisonTranslationResult>) -> Unit
    ) {
        synchronized(ownershipLock) {
            if (generation < latestGeneration || generation <= invalidatedThroughGeneration) return
            latestGeneration = generation
            generations.set(generation)

            comparisonJob?.cancel(CancellationException("New comparison requested"))
            comparisonJob = null

            val effectiveIds = request.translatorIds
                .asSequence()
                .distinct()
                .filter { it != request.primaryTranslatorId }
                .toList()

            val resolved = effectiveIds.map { id ->
                id to activeServiceManager.resolve<Translator>(id, ServiceRole.TRANSLATOR)
            }
            val initial = resolved.map { (id, active) ->
                ComparisonTranslationResult(
                    serviceId = id,
                    serviceName = active?.service?.name,
                    status = if (active == null) ComparisonStatus.FAILURE else ComparisonStatus.LOADING,
                    errorMessage = if (active == null) "Service unavailable." else null
                )
            }
            updateState(initial)

            if (resolved.isEmpty()) return

            val resultLock = Mutex()
            val results = initial.toMutableList()
            fun isCurrent(): Boolean = generations.get() == generation

            suspend fun publish(index: Int, result: ComparisonTranslationResult) {
                if (!isCurrent()) return
                resultLock.withLock {
                    if (!isCurrent()) return@withLock
                    results[index] = result
                    updateState(results.toList())
                }
            }

            comparisonJob = scope.launch {
                supervisorScope {
                    val comparisonParent = coroutineContext[Job]
                    resolved.mapIndexed { index, (id, active) ->
                        launch {
                        if (active == null || !isCurrent()) return@launch
                        val translator = active.service
                        val compatibilityError = languageCompatibilityError(
                            translator.supportedLanguages,
                            request.sourceLanguage,
                            request.targetLanguage
                        )
                        if (compatibilityError != null) {
                            publish(
                                index,
                                ComparisonTranslationResult(
                                    serviceId = id,
                                    serviceName = translator.name,
                                    status = ComparisonStatus.FAILURE,
                                    errorMessage = compatibilityError
                                )
                            )
                            return@launch
                        }

                        try {
                            val response = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
                                translator.translate(
                                    TranslationRequest(
                                        text = request.text,
                                        sourceLanguage = request.sourceLanguage,
                                        targetLanguage = request.targetLanguage
                                    )
                                )
                            }
                            if (response == null) {
                                publishFailure(index, id, translator.name, "Translation timed out.", ::publish)
                            } else {
                                response.fold(
                                    success = { translated ->
                                        publish(
                                            index,
                                            ComparisonTranslationResult(
                                                serviceId = id,
                                                serviceName = translator.name,
                                                status = ComparisonStatus.SUCCESS,
                                                text = translated.translatedText
                                            )
                                        )
                                    },
                                    failure = { error ->
                                        publishFailure(
                                            index,
                                            id,
                                            translator.name,
                                            error.shortSummary(),
                                            ::publish
                                        )
                                    }
                                )
                            }
                        } catch (cancellation: CancellationException) {
                            if (!isCurrent() || comparisonParent?.isActive != true) {
                                throw cancellation
                            }
                            publishFailure(
                                index,
                                id,
                                translator.name,
                                "Translation cancelled.",
                                ::publish
                            )
                        } catch (error: Exception) {
                            logger.error("Comparison translation failed for '$id'", error)
                            publishFailure(index, id, translator.name, error.shortSummary(), ::publish)
                        }
                        }
                    }.joinAll()
                }
            }
        }
    }

    private suspend fun publishFailure(
        index: Int,
        serviceId: String,
        serviceName: String,
        message: String,
        publish: suspend (Int, ComparisonTranslationResult) -> Unit
    ) {
        publish(
            index,
            ComparisonTranslationResult(
                serviceId = serviceId,
                serviceName = serviceName,
                status = ComparisonStatus.FAILURE,
                errorMessage = message.safeSummary()
            )
        )
    }

    private fun languageCompatibilityError(
        supportedLanguages: SupportedLanguages,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode
    ): String? = when (supportedLanguages) {
        SupportedLanguages.All,
        SupportedLanguages.Dynamic -> null
        is SupportedLanguages.Specific -> {
            val sourceSupported = sourceLanguage in supportedLanguages.languages
            val targetSupported = targetLanguage in supportedLanguages.languages
            if (sourceSupported && targetSupported) null else "Language pair is not supported."
        }
    }

    private fun String.safeSummary(): String =
        replace(Regex("\\s+"), " ").trim().take(160).ifBlank { "Translation failed." }
}

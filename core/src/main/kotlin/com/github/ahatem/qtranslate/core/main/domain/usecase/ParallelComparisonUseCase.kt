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
        comparisonJob?.cancel(CancellationException("Comparison invalidated"))
        comparisonJob = null
    }

    fun start(
        request: ParallelComparisonRequest,
        generation: Long,
        updateState: (List<ComparisonTranslationResult>) -> Unit
    ) {
        // Never call out to the caller's state while holding the comparison ownership lock:
        // the caller may be publishing under its own lock, and nesting the two in opposite
        // orders is a deadlock.
        synchronized(ownershipLock) {
            if (generation < latestGeneration || generation <= invalidatedThroughGeneration) return
            latestGeneration = generation
            comparisonJob?.cancel(CancellationException("New comparison requested"))
            comparisonJob = null
        }

        val effectiveIds = request.translatorIds
            .asSequence()
            .distinct()
            .filter { it != request.primaryTranslatorId }
            .toList()

        // Unavailable configured ids stay stored for Settings recovery but are
        // never executed and never rendered as fake provider cards — only
        // translators that actually resolve take part in the comparison.
        val resolved = effectiveIds.mapNotNull { id ->
            activeServiceManager.resolve<Translator>(id, ServiceRole.TRANSLATOR)
                ?.let { id to it }
        }
        val initial = resolved.map { (id, active) ->
            ComparisonTranslationResult(
                serviceId = id,
                serviceName = active.service.name,
                status = ComparisonStatus.LOADING
            )
        }

        val resultLock = Mutex()
        val results = initial.toMutableList()
        // Validity includes an invalidation of this very generation: bumping only the
        // executor generation is not enough once cancellation can no longer stop a writer
        // (the initial rows are published before the comparison job exists).
        fun isCurrent(): Boolean = synchronized(ownershipLock) {
            generation == latestGeneration && generation > invalidatedThroughGeneration
        }

        // Service resolution happened after the ownership claim released the lock, so the
        // generation may have been invalidated in the meantime. The first publication therefore
        // takes exactly the same validity check as every later result: a stale start must not
        // resurrect LOADING rows after the state was cleared.
        val publishedInitial = resultLock.withLock {
            if (!isCurrent()) return@withLock false
            updateState(initial)
            true
        }
        if (!publishedInitial) return

        if (resolved.isEmpty()) return

        suspend fun publish(index: Int, result: ComparisonTranslationResult) {
            if (!isCurrent()) return
            resultLock.withLock {
                if (!isCurrent()) return@withLock
                results[index] = result
                updateState(results.toList())
            }
        }

        val job = scope.launch {
            supervisorScope {
                val comparisonParent = coroutineContext[Job]
                resolved.mapIndexed { index, (id, active) ->
                    launch {
                    if (!isCurrent()) return@launch
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
                        logger.error("Comparison translation failed for '$id': ${error::class.simpleName}")
                        publishFailure(index, id, translator.name, error.shortSummary(), ::publish)
                    }
                    }
                }.joinAll()
            }
        }

        // Publish the job only if this generation still owns the executor; otherwise it is
        // already superseded and must not be left running.
        synchronized(ownershipLock) {
            if (generation == latestGeneration && generation > invalidatedThroughGeneration) {
                comparisonJob = job
            } else {
                job.cancel(CancellationException("Comparison superseded"))
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

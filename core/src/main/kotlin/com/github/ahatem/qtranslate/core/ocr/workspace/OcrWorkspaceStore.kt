package com.github.ahatem.qtranslate.core.ocr.workspace

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.core.ocr.OcrExtractionOutcome
import com.github.ahatem.qtranslate.core.ocr.OcrExtractionUseCase
import com.github.ahatem.qtranslate.core.ocr.OcrProviderInfo
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.ocr.preprocessOcrText
import com.github.ahatem.qtranslate.core.ocr.resolveOcrLanguage
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** A typed workspace failure the UI can localize without showing provider bodies. */
sealed interface OcrWorkspaceError {
    data object NoImage : OcrWorkspaceError
    data object NoProvider : OcrWorkspaceError
    data object NoText : OcrWorkspaceError
    data object Timeout : OcrWorkspaceError
    data class Failed(val summary: String) : OcrWorkspaceError
}

/** Everything the OCR Workspace renders, excluding the canonical translation workspace state. */
data class OcrWorkspaceState(
    val image: ImageData? = null,
    val imageLabel: String = "",
    val providers: List<OcrProviderInfo> = emptyList(),
    val providerId: String? = null,
    val languages: List<LanguageCode> = emptyList(),
    val language: LanguageCode = LanguageCode.AUTO,
    val recognizedText: String = "",
    val isEdited: Boolean = false,
    val isRecognizing: Boolean = false,
    val error: OcrWorkspaceError? = null,
    val regions: List<OCRTextRegion> = emptyList(),
    val showAnnotations: Boolean = false,
) {
    val hasImage: Boolean get() = image != null
    val hasText: Boolean get() = recognizedText.isNotBlank()
    val canRunOcr: Boolean get() = image != null && providerId != null && !isRecognizing
    val canTranslate: Boolean get() = hasText && !isRecognizing
}

/**
 * Owns the OCR half of the OCR Workspace: the current image, provider/language choice, the
 * recognized/edited text, and exactly one in-flight recognition request.
 *
 * Translation is deliberately absent. The workspace asks the canonical [MainStore] translation
 * workflow to translate its current text, so translation ownership, cancellation, history,
 * comparison and auto-copy stay in one place. The workspace only owns which OCR source/edit is
 * current.
 *
 * Every mutation of OCR result state is fenced by [generation]. A late result from a superseded
 * provider switch, re-run, new image or closed window is dropped before it can touch state.
 */
class OcrWorkspaceStore(
    private val scope: CoroutineScope,
    private val extraction: OcrExtractionUseCase,
    private val catalog: OcrServiceCatalog,
    private val configuration: () -> Configuration,
    private val ocrSettings: () -> OcrSettings = { configuration().ocr },
    private val translationSource: () -> LanguageCode = { LanguageCode.AUTO },
) {
    private val _state = MutableStateFlow(
        OcrWorkspaceState(showAnnotations = ocrSettings().showWorkspaceAnnotations)
    )
    val state: StateFlow<OcrWorkspaceState> = _state.asStateFlow()

    private val generation = AtomicLong()
    private var ocrJob: Job? = null
    @Volatile
    private var closed = false

    init {
        // The catalog is shared and eagerly populated. Adopt its current value synchronously so
        // an image opened immediately after construction already has a provider to recognize with.
        applyProviders(catalog.providers.value)
        scope.launch {
            catalog.providers.collect { providers ->
                if (closed) return@collect
                applyProviders(providers)
            }
        }
    }

    /**
     * Applies catalog changes without letting an in-flight request from the old provider publish
     * under the new provider's label. A changed provider or language is a new recognition context.
     */
    private fun applyProviders(providers: List<OcrProviderInfo>) {
        val before = _state.value
        val next = before.withProviders(providers)
        val contextChanged = next.providerId != before.providerId || next.language != before.language
        // A dynamic provider starts with an empty language list and fills it asynchronously.
        // That metadata arrival is not a user-initiated context change: the in-flight request
        // was sent with AUTO and the provider resolves it. Cancelling here made the first
        // workspace auto-run disappear with no error.
        val metadataOnly = before.providerId == next.providerId &&
            before.languages.isEmpty() &&
            next.languages.isNotEmpty()
        if (contextChanged && before.isRecognizing && !metadataOnly) invalidateRequest()
        _state.update { current ->
            val updated = current.withProviders(providers)
            if (contextChanged && !metadataOnly && current.isRecognizing) {
                updated.copy(isRecognizing = false, error = null)
            } else {
                updated
            }
        }
    }

    fun openImage(image: ImageData, label: String, autoRun: Boolean = true) {
        if (closed) return
        invalidateRequest()
        _state.update {
            it.copy(
                image = image,
                imageLabel = label,
                recognizedText = "",
                isEdited = false,
                regions = emptyList(),
                error = null,
                isRecognizing = false,
            )
        }
        if (autoRun) runOcr()
    }

    /**
     * Adopts an OCR result the capture workflow already produced. This is used when the
     * configured destination opens the workspace: running recognition a second time would be
     * both slower and a different result than the one the workflow acted on.
     */
    fun openWithResult(
        image: ImageData,
        label: String,
        text: String,
        regions: List<OCRTextRegion>,
    ) {
        if (closed) return
        invalidateRequest()
        _state.update {
            it.copy(
                image = image,
                imageLabel = label,
                recognizedText = text,
                isEdited = false,
                regions = regions,
                error = null,
                isRecognizing = false,
            )
        }
    }

    fun selectProvider(providerId: String?) {
        if (closed) return
        val provider = _state.value.providers.firstOrNull { it.id == providerId }
        invalidateRequest()
        _state.update { current ->
            current.copy(
                providerId = provider?.id,
                languages = provider?.languages.orEmpty(),
                language = defaultLanguage(provider),
                isRecognizing = false,
                error = null,
            )
        }
    }

    fun selectLanguage(language: LanguageCode) {
        if (closed) return
        _state.update { it.copy(language = language) }
    }

    fun runOcr() {
        if (closed) return
        val snapshot = _state.value
        val image = snapshot.image
        if (image == null) {
            _state.update { it.copy(error = OcrWorkspaceError.NoImage) }
            return
        }
        val providerId = snapshot.providerId
        if (providerId == null) {
            _state.update { it.copy(error = OcrWorkspaceError.NoProvider) }
            return
        }

        invalidateRequest()
        val requestGeneration = generation.incrementAndGet()
        _state.update { it.copy(isRecognizing = true, error = null) }
        ocrJob = scope.launch {
            val outcome = extraction.extract(
                image = image,
                language = snapshot.language,
                serviceId = providerId,
            )
            if (closed || generation.get() != requestGeneration) return@launch

            _state.update { current ->
                // Re-check at publication time, not only before the update: invalidation can
                // happen on another thread between the first check and this lambda.
                if (closed || generation.get() != requestGeneration) {
                    current
                } else when (outcome) {
                    is OcrExtractionOutcome.Success -> {
                        val text = preprocessOcrText(outcome.result.text, ocrSettings())
                        current.copy(
                            recognizedText = text,
                            isEdited = false,
                            isRecognizing = false,
                            error = null,
                            regions = outcome.result.regions,
                        )
                    }
                    OcrExtractionOutcome.NoText -> current.copy(
                        recognizedText = "",
                        isEdited = false,
                        isRecognizing = false,
                        error = OcrWorkspaceError.NoText,
                        regions = emptyList(),
                    )
                    OcrExtractionOutcome.Timeout -> current.copy(
                        isRecognizing = false,
                        error = OcrWorkspaceError.Timeout,
                    )
                    OcrExtractionOutcome.NoService -> current.copy(
                        isRecognizing = false,
                        error = OcrWorkspaceError.NoProvider,
                    )
                    is OcrExtractionOutcome.Failure -> current.copy(
                        isRecognizing = false,
                        error = OcrWorkspaceError.Failed(outcome.summary),
                    )
                }
            }
        }
    }

    fun cancelOcr() {
        if (closed) return
        invalidateRequest()
        _state.update { it.copy(isRecognizing = false) }
    }

    fun updateText(text: String) {
        if (closed) return
        _state.update { it.copy(recognizedText = text, isEdited = true) }
    }

    fun toggleAnnotations() {
        if (closed) return
        _state.update { it.copy(showAnnotations = !it.showAnnotations) }
    }

    /** Cancels owned work and permanently stops this store from mutating state. */
    fun close() {
        if (closed) return
        closed = true
        invalidateRequest()
        _state.update { it.copy(isRecognizing = false) }
    }

    private fun invalidateRequest() {
        generation.incrementAndGet()
        ocrJob?.cancel()
        ocrJob = null
    }

    private fun OcrWorkspaceState.withProviders(providers: List<OcrProviderInfo>): OcrWorkspaceState {
        val configured = configuration().getActivePreset()
            ?.selectedServices
            ?.get(com.github.ahatem.qtranslate.api.plugin.ServiceRole.OCR)
        val selected = providerId
            ?.takeIf { id -> providers.any { it.id == id } }
            ?: configured?.takeIf { id -> providers.any { it.id == id } }
            ?: providers.firstOrNull()?.id
        val provider = providers.firstOrNull { it.id == selected }
        val language = language.takeIf { candidate ->
            provider != null && (provider.languages.isEmpty() || candidate in provider.languages)
        } ?: defaultLanguage(provider)
        return copy(
            providers = providers,
            providerId = selected,
            languages = provider?.languages.orEmpty(),
            language = language,
        )
    }

    private fun defaultLanguage(provider: OcrProviderInfo?): LanguageCode {
        val languages = provider?.languages.orEmpty()
        if (languages.isEmpty()) return LanguageCode.AUTO
        val configured = ocrSettings().recognitionLanguage
        if (configured.isNotBlank()) {
            val resolved = resolveOcrLanguage(configured, translationSource(), languages)
            if (resolved == LanguageCode.AUTO || resolved in languages) return resolved
            languages.firstOrNull { it == LanguageCode.AUTO }?.let { return it }
            return languages.first()
        }
        val source = translationSource()
        if (source != LanguageCode.AUTO && source in languages) return source
        return languages.firstOrNull { it == LanguageCode.AUTO } ?: languages.first()
    }
}

/** The current provider descriptor, or null when none is usable. */
fun OcrWorkspaceState.provider(): OcrProviderInfo? = providers.firstOrNull { it.id == providerId }

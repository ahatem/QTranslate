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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A typed workspace failure the UI can localize without showing provider bodies. */
sealed interface OcrWorkspaceError {
    data object NoImage : OcrWorkspaceError
    data object NoProvider : OcrWorkspaceError
    data object NoText : OcrWorkspaceError
    data object Timeout : OcrWorkspaceError
    data object ImageOpenFailed : OcrWorkspaceError
    data object ClipboardEmpty : OcrWorkspaceError
    data class Failed(val summary: String) : OcrWorkspaceError
}

/** Everything the OCR Workspace renders, excluding the canonical translation workspace state. */
data class OcrWorkspaceState(
    val image: ImageData? = null,
    val imageLabel: String = "",
    val providers: List<OcrProviderInfo> = emptyList(),
    val providerId: String? = null,
    val languages: List<LanguageCode> = emptyList(),
    /** The language requested for the next recognition run. */
    val language: LanguageCode = LanguageCode.AUTO,
    /**
     * The language that belongs to [recognizedText] itself, from the provider's detection or
     * the explicit language that request used. Never rewritten by changing [language].
     */
    val recognizedLanguage: LanguageCode? = null,
    val recognizedText: String = "",
    val isEdited: Boolean = false,
    val isRecognizing: Boolean = false,
    /** A new image input has been claimed and its bytes are still being materialized. */
    val isLoadingImage: Boolean = false,
    val error: OcrWorkspaceError? = null,
    val regions: List<OCRTextRegion> = emptyList(),
    val showAnnotations: Boolean = false,
) {
    val hasImage: Boolean get() = image != null
    val hasText: Boolean get() = recognizedText.isNotBlank()
    val canRunOcr: Boolean get() = image != null && providerId != null && !isRecognizing && !isLoadingImage
    val canTranslate: Boolean get() = hasText && !isRecognizing && !isLoadingImage
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
 * ### Ownership
 * Image-input ownership and OCR-request ownership move under one lock. Every state publication
 * checks that ownership *inside* the same lock that commits it, so a superseded request cannot
 * publish after a newer one has taken over. The lock never spans provider I/O or coroutine
 * starts; those are detached and cancelled/started afterwards.
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

    private val lock = Any()
    private var generation: Long = 0L
    private var inputGeneration: Long = 0L
    private var ocrJob: Job? = null
    private var closed: Boolean = false

    /** Last successfully displayed input, restored when a new materialization fails. */
    private var lastGoodState: OcrWorkspaceState? = null

    /**
     * Whether the current language came from an explicit user choice. Catalog refreshes and
     * dynamic language lists must not overwrite that, while the initial adoption of a saved
     * recognition language must still happen after a dynamic provider's list arrives.
     */
    private var languageChosenByUser: Boolean = false

    /**
     * Test seams around the publication attempt. Production leaves both null. A deterministic
     * race test can hold one request exactly where an implementation without the ownership lock
     * would be about to commit, then let a newer request own the store.
     */
    internal var beforePublication: ((token: Long) -> Unit)? = null
    internal var afterPublication: ((token: Long) -> Unit)? = null

    private data class StartedOcr(val previousJob: Job?, val job: Job)

    init {
        // The catalog is shared and eagerly populated. Adopt its current value synchronously so
        // an image opened immediately after construction already has a provider to recognize with.
        applyProviders(catalog.providers.value)
        scope.launch {
            catalog.providers.collect { providers ->
                applyProviders(providers)
            }
        }
    }

    /**
     * Applies catalog changes without letting an in-flight request from the old provider publish
     * under the new provider's label. A changed provider or language is a new recognition context.
     */
    private fun applyProviders(providers: List<OcrProviderInfo>) {
        var previous: Job? = null
        synchronized(lock) {
            if (closed) return
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
            if (contextChanged && before.isRecognizing && !metadataOnly) {
                previous = invalidateLocked()
                _state.value = next.copy(isRecognizing = false, error = null)
            } else {
                _state.value = next
            }
        }
        previous?.cancel()
    }

    /**
     * Claims a new image input synchronously, before any decoding or encoding starts.
     *
     * Every pending operation that belonged to the previous image is invalidated here, so the
     * newest input owns the workspace from the moment the user asks for it.
     */
    fun beginImageInput(): Long? {
        var previous: Job? = null
        val token = synchronized(lock) {
            if (closed) return null
            val next = ++inputGeneration
            previous = invalidateLocked()
            val current = _state.value
            if (!current.isLoadingImage && (current.image != null || current.hasText)) {
                lastGoodState = current
            }
            _state.value = current.copy(
                image = null,
                imageLabel = "",
                recognizedText = "",
                recognizedLanguage = null,
                isEdited = false,
                regions = emptyList(),
                error = null,
                isRecognizing = false,
                isLoadingImage = true,
            )
            next
        }
        previous?.cancel()
        return token
    }

    /**
     * Publishes materialized bytes for [token], or refuses when a newer input already owns the
     * workspace. The optional auto-run starts under the same ownership gate, so it cannot run
     * against a later image.
     */
    fun completeImageInput(
        token: Long,
        image: ImageData,
        label: String,
        autoRun: Boolean,
        initialText: String? = null,
        initialRegions: List<OCRTextRegion> = emptyList(),
        recognizedLanguage: LanguageCode? = null,
    ): Boolean {
        var started: StartedOcr? = null
        synchronized(lock) {
            if (closed || token != inputGeneration) return false
            val current = _state.value
            val next = current.copy(
                image = image,
                imageLabel = label,
                recognizedText = initialText.orEmpty(),
                recognizedLanguage = if (initialText != null) recognizedLanguage else null,
                isEdited = false,
                regions = if (initialText != null) initialRegions else emptyList(),
                error = null,
                isRecognizing = false,
                isLoadingImage = false,
            )
            _state.value = next
            lastGoodState = next
            if (autoRun && initialText == null) started = startOcrLocked(next)
        }
        started?.previousJob?.cancel()
        started?.job?.start()
        return true
    }

    /** Publishes a typed failure only while [token] still owns the image input. */
    fun failImageInput(token: Long, error: OcrWorkspaceError): Boolean {
        synchronized(lock) {
            if (closed || token != inputGeneration) return false
            val fallback = lastGoodState
            _state.update { current ->
                if (fallback != null) {
                    current.copy(
                        image = fallback.image,
                        imageLabel = fallback.imageLabel,
                        recognizedText = fallback.recognizedText,
                        recognizedLanguage = fallback.recognizedLanguage,
                        isEdited = fallback.isEdited,
                        regions = fallback.regions,
                        isRecognizing = false,
                        isLoadingImage = false,
                        error = error,
                    )
                } else {
                    current.copy(
                        image = null,
                        imageLabel = "",
                        recognizedText = "",
                        recognizedLanguage = null,
                        regions = emptyList(),
                        isRecognizing = false,
                        isLoadingImage = false,
                        error = error,
                    )
                }
            }
            return true
        }
    }

    fun isImageInputCurrent(token: Long): Boolean = synchronized(lock) {
        !closed && token == inputGeneration
    }

    /** Convenience for callers that already hold materialized bytes. */
    fun openImage(image: ImageData, label: String, autoRun: Boolean = true) {
        val token = beginImageInput() ?: return
        completeImageInput(token, image, label, autoRun)
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
        recognizedLanguage: LanguageCode? = null,
    ) {
        val token = beginImageInput() ?: return
        completeImageInput(
            token = token,
            image = image,
            label = label,
            autoRun = false,
            initialText = text,
            initialRegions = regions,
            recognizedLanguage = recognizedLanguage,
        )
    }

    fun selectProvider(providerId: String?) {
        var previous: Job? = null
        synchronized(lock) {
            if (closed) return
            val provider = _state.value.providers.firstOrNull { it.id == providerId }
            previous = invalidateLocked()
            languageChosenByUser = false
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
        previous?.cancel()
    }

    fun selectLanguage(language: LanguageCode) {
        synchronized(lock) {
            if (closed) return
            languageChosenByUser = true
            _state.update { it.copy(language = language) }
        }
    }

    fun runOcr() {
        var started: StartedOcr? = null
        synchronized(lock) {
            if (closed) return
            val snapshot = _state.value
            if (snapshot.isLoadingImage) return
            if (snapshot.image == null) {
                _state.value = snapshot.copy(error = OcrWorkspaceError.NoImage)
                return
            }
            if (snapshot.providerId == null) {
                _state.value = snapshot.copy(error = OcrWorkspaceError.NoProvider)
                return
            }
            started = startOcrLocked(snapshot)
        }
        started?.previousJob?.cancel()
        started?.job?.start()
    }

    fun cancelOcr() {
        var previous: Job? = null
        synchronized(lock) {
            if (closed) return
            previous = invalidateLocked()
            _state.update { it.copy(isRecognizing = false) }
        }
        previous?.cancel()
    }

    fun updateText(text: String) {
        synchronized(lock) {
            if (closed) return
            // Editing changes the text, not the language it was recognized in.
            _state.update { it.copy(recognizedText = text, isEdited = true) }
            // Loading edits are temporary. They cannot redefine the successfully materialized
            // image/result that failure recovery must restore.
            if (!_state.value.isLoadingImage) lastGoodState = _state.value
        }
    }

    fun toggleAnnotations() {
        synchronized(lock) {
            if (closed) return
            _state.update { it.copy(showAnnotations = !it.showAnnotations) }
        }
    }

    /** Cancels owned work and permanently stops this store from mutating state. */
    fun close() {
        var previous: Job? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1
            previous = ocrJob
            ocrJob = null
            _state.update { it.copy(isRecognizing = false) }
        }
        previous?.cancel()
    }

    private fun invalidateLocked(): Job? {
        generation += 1
        val previous = ocrJob
        ocrJob = null
        return previous
    }

    /**
     * Starts recognition from a snapshot that already belongs to the caller while the lock is
     * held. The job is lazy so its slot is installed before any provider code can run.
     */
    private fun startOcrLocked(snapshot: OcrWorkspaceState): StartedOcr? {
        val image = snapshot.image ?: return null
        val providerId = snapshot.providerId ?: return null
        val previous = invalidateLocked()
        val token = generation
        _state.value = snapshot.copy(isRecognizing = true, error = null)
        val requestLanguage = snapshot.language
        val candidate = scope.launch(start = CoroutineStart.LAZY) {
            val self = kotlinx.coroutines.currentCoroutineContext()[Job]
            val outcome = extraction.extract(
                image = image,
                language = requestLanguage,
                serviceId = providerId,
            )
            beforePublication?.invoke(token)
            var published = false
            synchronized(lock) {
                if (!closed && token == generation) {
                    // Re-check and commit under the same lock: no newer image, provider, re-run
                    // or close can become current between these lines.
                    _state.value = when (outcome) {
                        is OcrExtractionOutcome.Success -> {
                            val text = preprocessOcrText(outcome.result.text, ocrSettings())
                            _state.value.copy(
                                recognizedText = text,
                                recognizedLanguage = outcome.result.detectedLanguage
                                    ?.takeIf { it != LanguageCode.AUTO }
                                    ?: requestLanguage.takeIf { it != LanguageCode.AUTO },
                                isEdited = false,
                                isRecognizing = false,
                                error = null,
                                regions = outcome.result.regions,
                            )
                        }
                        OcrExtractionOutcome.NoText -> _state.value.copy(
                            recognizedText = "",
                            recognizedLanguage = null,
                            isEdited = false,
                            isRecognizing = false,
                            error = OcrWorkspaceError.NoText,
                            regions = emptyList(),
                        )
                        OcrExtractionOutcome.Timeout -> _state.value.copy(
                            isRecognizing = false,
                            error = OcrWorkspaceError.Timeout,
                        )
                        OcrExtractionOutcome.NoService -> _state.value.copy(
                            isRecognizing = false,
                            error = OcrWorkspaceError.NoProvider,
                        )
                        is OcrExtractionOutcome.Failure -> _state.value.copy(
                            isRecognizing = false,
                            error = OcrWorkspaceError.Failed(outcome.summary),
                        )
                    }
                    if (ocrJob === self) ocrJob = null
                    lastGoodState = _state.value
                    published = true
                }
            }
            if (!published) {
                afterPublication?.invoke(token)
                return@launch
            }
            afterPublication?.invoke(token)
        }
        ocrJob = candidate
        return StartedOcr(previous, candidate)
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
        val language = if (languageChosenByUser &&
            (provider == null ||
                provider.languages.isEmpty() ||
                language in provider.languages ||
                language == LanguageCode.AUTO)
        ) {
            language
        } else {
            defaultLanguage(provider)
        }
        return copy(
            providers = providers,
            providerId = selected,
            languages = provider?.languages.orEmpty(),
            language = language,
        )
    }

    /**
     * Resolves the persisted recognition-language policy against [provider].
     *
     * Blank means "follow the translation source". An explicit tag wins when the provider
     * declares it. AUTO is passed through: it is the request contract's default and providers
     * such as System OCR resolve it internally even when their advertised list does not include
     * it. Anything unsupported falls back to AUTO, then to the provider's first language.
     */
    private fun defaultLanguage(provider: OcrProviderInfo?): LanguageCode {
        val languages = provider?.languages.orEmpty()
        if (languages.isEmpty()) return LanguageCode.AUTO
        val configured = ocrSettings().recognitionLanguage
        val resolved = if (configured.isNotBlank()) {
            resolveOcrLanguage(configured, translationSource(), languages)
        } else {
            translationSource()
        }
        if (resolved == LanguageCode.AUTO) return LanguageCode.AUTO
        if (resolved in languages) return resolved
        return languages.firstOrNull { it == LanguageCode.AUTO } ?: languages.first()
    }
}

/** The current provider descriptor, or null when none is usable. */
fun OcrWorkspaceState.provider(): OcrProviderInfo? = providers.firstOrNull { it.id == providerId }

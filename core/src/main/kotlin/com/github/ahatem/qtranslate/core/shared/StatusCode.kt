package com.github.ahatem.qtranslate.core.shared

/**
 * Typed status identifier emitted by use cases for the status bar.
 *
 * Use cases emit a code + any needed parameters; the UI layer resolves the
 * user-visible, localized string — keeping business logic free of hardcoded strings.
 */
sealed class StatusCode {

    // ---- Translation ----

    object Translating : StatusCode()
    object TranslationComplete : StatusCode()
    object TranslationCancelled : StatusCode()
    object TranslationTimeout : StatusCode()
    data class TranslationFailed(val summary: String) : StatusCode()
    object NoTranslatorActive : StatusCode()
    object PerformingBackwardTranslation : StatusCode()
    data class UnexpectedError(val summary: String) : StatusCode()

    // ---- Text-to-Speech ----

    object NoTextToSpeak : StatusCode()
    object CannotDetermineLanguage : StatusCode()
    object NoTtsServiceActive : StatusCode()
    data class TtsLanguageNotSupported(val serviceName: String) : StatusCode()
    object ConvertingToSpeech : StatusCode()
    object TtsTimeout : StatusCode()
    object PlayingAudio : StatusCode()
    object AudioPlaybackComplete : StatusCode()
    object TtsStopped : StatusCode()
    object DownloadingAudio : StatusCode()
    object AudioDownloadFailed : StatusCode()
    data class TtsFailed(val summary: String) : StatusCode()

    // ---- OCR ----

    object NoOcrServiceActive : StatusCode()
    object RecognizingText : StatusCode()
    object OcrTimeout : StatusCode()
    object NoTextInImage : StatusCode()
    object OcrComplete : StatusCode()
    object OcrTextCopied : StatusCode()

    /** Confirms a copy-to-clipboard action that would otherwise give no visible feedback. */
    object TextCopied : StatusCode()
    data class OcrFailed(val summary: String) : StatusCode()

    // ---- Summarize ----

    object NoSummarizerActive : StatusCode()
    object Summarizing : StatusCode()
    object SummarizeTimeout : StatusCode()
    object SummaryReady : StatusCode()
    data class SummarizeFailed(val summary: String) : StatusCode()

    // ---- Rewrite ----

    object NoRewriterActive : StatusCode()
    object Rewriting : StatusCode()
    object RewriteTimeout : StatusCode()
    object RewriteReady : StatusCode()
    data class RewriteFailed(val summary: String) : StatusCode()

    // ---- Spell Check ----

    object SpellCheckTimeout : StatusCode()
    data class SpellCheckFailed(val summary: String) : StatusCode()

    // ---- Dictionary ----

    object NoWordToLookup : StatusCode()
    object NoDictionaryServiceActive : StatusCode()
    object LookingUpWord : StatusCode()
    object DictionaryReady : StatusCode()
    data class DictionaryNotFound(val word: String) : StatusCode()
    object DictionaryTimeout : StatusCode()
    data class DictionaryFailed(val summary: String) : StatusCode()

    // ---- Image search ----

    object NoTermToIllustrate : StatusCode()
    object NoImageSearchServiceActive : StatusCode()
    object SearchingImages : StatusCode()
    object ImageSearchReady : StatusCode()
    data class ImagesNotFound(val term: String) : StatusCode()
    object ImageSearchTimeout : StatusCode()
    data class ImageSearchFailed(val summary: String) : StatusCode()

    // ---- Updates ----

    data class AlreadyUpToDate(val version: String) : StatusCode()
    object UpdateCheckNetworkError : StatusCode()
    object UpdateCheckParseError : StatusCode()
    object UpdateCheckUnknownError : StatusCode()
}

package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class StatusBarController(
    private val statusBar: StatusBar,
    private val notificationPopover: NotificationPopover,
    private val iconManager: IconManager,
    private val localizer: LocalizationManager,
    private val scope: CoroutineScope,
    private val defaultMessage: String,
) {
    private var clearMessageJob: Job? = null
    private var currentMessage: String = defaultMessage
    private var currentType: NotificationType = NotificationType.INFO
    private var isLoading: Boolean = false
    private var unreadCount: Int = 0

    private val errorDetailPopup = ErrorDetailPopup(iconManager)

    /** Message currently shown in the popup, null when popup is hidden. */
    private var shownDetailMessage: String? = null

    init {
        errorDetailPopup.errorLabel   = localizer.getString("main_window_status_bar.error_detail_error")
        errorDetailPopup.warningLabel = localizer.getString("main_window_status_bar.error_detail_warning")
        errorDetailPopup.copyLabel    = localizer.getString("main_window_status_bar.error_detail_copy")
        errorDetailPopup.copiedLabel  = localizer.getString("main_window_status_bar.error_detail_copied")
        errorDetailPopup.closeLabel   = localizer.getString("common.close")

        statusBar.onErrorClicked = { message ->
            shownDetailMessage = message
            errorDetailPopup.show(message, currentType, statusBar)
        }

        render()
    }

    /** Called for transient action feedback ("Translating…", "Playing audio…"). */
    fun handleEvent(event: MainEvent.UpdateStatusBar) {
        clearMessageJob?.cancel()
        currentMessage = resolveStatusMessage(event.code)
        currentType = event.type
        render()
        if (event.isTemporary) {
            val snapshot = currentMessage
            clearMessageJob = scope.launch {
                delay(AppConstants.STATUS_MESSAGE_DURATION_MS)
                if (currentMessage == snapshot) {
                    currentMessage = defaultMessage
                    currentType = NotificationType.INFO
                    render()
                }
            }
        }
    }

    /** Called for background/system events - adds to popover, updates bell badge. */
    fun addToPopover(notification: AppNotification) {
        val message = resolveNotificationMessage(notification.code)
        notificationPopover.addNotification(NotificationPopover.NotificationEntry(message, notification.type))
        unreadCount++
        render()
    }

    /** Reflects the main loading state (translation / OCR in progress). */
    fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        render()
    }

    /** Called when the user clears all notifications. */
    fun onPopoverCleared() {
        unreadCount = 0
        render()
    }

    private fun bellTooltip(): String {
        val base = localizer.getString("main_window_status_bar.notifications_tooltip")
        return if (unreadCount > 0) "$base ($unreadCount)" else base
    }

    private fun render() {
        // Auto-dismiss the detail popup when the displayed message changes or
        // when the type is no longer an error/warning.
        val isErrorOrWarning = currentType == NotificationType.ERROR || currentType == NotificationType.WARNING
        if (errorDetailPopup.isVisible && (!isErrorOrWarning || shownDetailMessage != currentMessage)) {
            errorDetailPopup.dismiss()
            shownDetailMessage = null
        }

        statusBar.render(
            StatusBarState(
                message = currentMessage,
                type = currentType,
                isLoading = isLoading,
                notificationTooltip = bellTooltip(),
                isNotificationButtonEnabled = true
            )
        )
    }

    private fun resolveStatusMessage(code: StatusCode): String = when (code) {
        StatusCode.Translating                  -> localizer.getString("status_bar.translating")
        StatusCode.TranslationComplete          -> localizer.getString("status_bar.translation_complete")
        StatusCode.TranslationCancelled         -> localizer.getString("status_bar.translation_cancelled")
        StatusCode.TranslationTimeout           -> localizer.getString("status_bar.translation_timeout")
        is StatusCode.TranslationFailed         -> localizer.getString("status_bar.translation_failed", code.summary)
        StatusCode.NoTranslatorActive           -> localizer.getString("status_bar.no_translator_active")
        StatusCode.ComparisonNeedsTwoTranslators -> localizer.getString("status_bar.comparison_needs_two_translators")
        StatusCode.PerformingBackwardTranslation -> localizer.getString("status_bar.performing_backward_translation")
        is StatusCode.UnexpectedError           -> localizer.getString("status_bar.unexpected_error", code.summary)
        StatusCode.NoTextToSpeak                -> localizer.getString("status_bar.no_text_to_speak")
        StatusCode.CannotDetermineLanguage      -> localizer.getString("status_bar.cannot_determine_language")
        StatusCode.NoTtsServiceActive           -> localizer.getString("status_bar.no_tts_active")
        is StatusCode.TtsLanguageNotSupported   -> localizer.getString("status_bar.tts_language_not_supported", code.serviceName)
        StatusCode.ConvertingToSpeech           -> localizer.getString("status_bar.converting_to_speech")
        StatusCode.TtsTimeout                   -> localizer.getString("status_bar.tts_timeout")
        StatusCode.PlayingAudio                 -> localizer.getString("status_bar.playing_audio")
        StatusCode.AudioPlaybackComplete        -> localizer.getString("status_bar.audio_playback_complete")
        StatusCode.TtsStopped                   -> localizer.getString("status_bar.tts_stopped")
        StatusCode.DownloadingAudio             -> localizer.getString("status_bar.downloading_audio")
        StatusCode.AudioDownloadFailed          -> localizer.getString("status_bar.audio_download_failed")
        is StatusCode.TtsFailed                 -> localizer.getString("status_bar.tts_failed", code.summary)
        StatusCode.NoOcrServiceActive           -> localizer.getString("status_bar.no_ocr_active")
        StatusCode.RecognizingText              -> localizer.getString("status_bar.recognizing_text")
        StatusCode.OcrTimeout                   -> localizer.getString("status_bar.ocr_timeout")
        StatusCode.NoTextInImage                -> localizer.getString("status_bar.no_text_in_image")
        StatusCode.OcrComplete                  -> localizer.getString("status_bar.ocr_complete")
        StatusCode.OcrTextCopied               -> localizer.getString("status_bar.ocr_text_copied")
        StatusCode.TextCopied                  -> localizer.getString("status_bar.text_copied")
        is StatusCode.OcrFailed                 -> localizer.getString("status_bar.ocr_failed", code.summary)
        StatusCode.NoSummarizerActive           -> localizer.getString("status_bar.no_summarizer_active")
        StatusCode.Summarizing                  -> localizer.getString("status_bar.summarizing")
        StatusCode.SummarizeTimeout             -> localizer.getString("status_bar.summarize_timeout")
        StatusCode.SummaryReady                 -> localizer.getString("status_bar.summary_ready")
        is StatusCode.SummarizeFailed           -> localizer.getString("status_bar.summarize_failed", code.summary)
        StatusCode.NoRewriterActive             -> localizer.getString("status_bar.no_rewriter_active")
        StatusCode.Rewriting                    -> localizer.getString("status_bar.rewriting")
        StatusCode.RewriteTimeout               -> localizer.getString("status_bar.rewrite_timeout")
        StatusCode.RewriteReady                 -> localizer.getString("status_bar.rewrite_ready")
        is StatusCode.RewriteFailed             -> localizer.getString("status_bar.rewrite_failed", code.summary)
        StatusCode.SpellCheckTimeout            -> localizer.getString("status_bar.spell_check_timeout")
        is StatusCode.SpellCheckFailed          -> localizer.getString("status_bar.spell_check_failed", code.summary)
        StatusCode.NoWordToLookup               -> localizer.getString("status_bar.no_word_to_lookup")
        StatusCode.NoDictionaryServiceActive    -> localizer.getString("status_bar.no_dictionary_active")
        StatusCode.LookingUpWord                -> localizer.getString("status_bar.looking_up_word")
        StatusCode.DictionaryReady              -> localizer.getString("status_bar.dictionary_ready")
        is StatusCode.DictionaryNotFound        -> localizer.getString("status_bar.dictionary_not_found", code.word)
        StatusCode.DictionaryTimeout            -> localizer.getString("status_bar.dictionary_timeout")
        is StatusCode.DictionaryFailed          -> localizer.getString("status_bar.dictionary_failed", code.summary)
        StatusCode.NoTermToIllustrate           -> localizer.getString("status_bar.no_term_to_illustrate")
        StatusCode.NoImageSearchServiceActive   -> localizer.getString("status_bar.no_image_search_active")
        StatusCode.SearchingImages              -> localizer.getString("status_bar.searching_images")
        StatusCode.ImageSearchReady             -> localizer.getString("status_bar.image_search_ready")
        is StatusCode.ImagesNotFound            -> localizer.getString("status_bar.images_not_found", code.term)
        StatusCode.ImageSearchTimeout           -> localizer.getString("status_bar.image_search_timeout")
        is StatusCode.ImageSearchFailed         -> localizer.getString("status_bar.image_search_failed", code.summary)
        is StatusCode.AlreadyUpToDate           -> localizer.getString("status_bar.already_up_to_date", code.version)
        StatusCode.UpdateCheckNetworkError      -> localizer.getString("status_bar.update_check_network_error")
        StatusCode.UpdateCheckParseError        -> localizer.getString("status_bar.update_check_parse_error")
        StatusCode.UpdateCheckUnknownError      -> localizer.getString("status_bar.update_check_unknown_error")
    }

    private fun resolveNotificationMessage(code: NotificationCode): String = when (code) {
        is NotificationCode.LanguageNotSupported ->
            localizer.getString("notifications.language_not_supported_format", code.lang, code.serviceId)
        is NotificationCode.TtsNotSupported ->
            localizer.getString("notifications.tts_not_supported_format", code.serviceId)
        is NotificationCode.UnknownError ->
            localizer.getString("notifications.unknown_error")
        is NotificationCode.Custom -> if (code.title.isNotBlank()) "${code.title}: ${code.body}" else code.body
        is NotificationCode.UpdateAvailable -> ""
    }
}

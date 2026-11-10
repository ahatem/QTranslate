package com.github.ahatem.qtranslate.plugins.speech

import java.util.Locale

/**
 * Provides localized UI strings for speech-services dialogs and messages.
 */
internal object SpeechLocalization {
    private val englishStrings = mapOf(
        "dialog_title" to "Download Vosk Model",
        "model_label" to "Model: %s",
        "source_label" to "Source: alphacephei.com (local offline storage)",
        "connecting" to "Connecting to server...",
        "pause" to "Pause",
        "resume" to "Resume",
        "cancel" to "Cancel",
        "close" to "Close",
        "resuming" to "Resuming download...",
        "cancelling" to "Cancelling...",
        "paused_stats" to "Paused at %s / %s (%d%%). Click Resume to continue.",
        "extracting" to "Extracting model files into local storage...",
        "done" to "Done! Model installed successfully.",
        "error" to "Error: %s",
        "remaining_sec" to "%ds remaining",
        "remaining_min_sec" to "%dm %ds remaining",
        "disk_space_error" to "Not enough free disk space to download and extract '%s'. Required: ~%s, available: %s.",
        "installed_marker" to "%s ✓ (installed)",
        "download_model_button" to "Download Model",
        "downloading_model_label" to "Downloading (%s)...",
        "test_success" to "Connection test succeeded.",
        "test_key_rejected" to "API key was rejected by the server.",
        "test_model_unknown" to "The model '%s' is not recognized by the server.",
        "test_model_not_audio" to "The model '%s' does not accept audio input.",
        "test_server_unreachable" to "Could not reach server: %s",
        "deepgram_unsupported_language" to "Deepgram model '%s' does not support language '%s'.",
        "deepgram_unsupported_multi" to "Deepgram model '%s' does not support multilingual detection (auto).",
        "import_chooser_title" to "Import Vosk Model (ZIP or Folder)",
        "import_cancelled" to "Model import cancelled.",
        "import_success" to "Successfully imported model '%s' into local storage.",
        "import_failed" to "Import failed: %s",
        "delete_unknown_model" to "Unknown model '%s'.",
        "delete_not_installed" to "Model '%s' is not downloaded on this machine.",
        "delete_success" to "Model '%s' has been successfully deleted from your computer.",
        "delete_failed" to "Failed to delete model '%s'. Please check file permissions.",
        "status_installed" to "✓ Installed and ready to use",
        "status_incomplete" to "incomplete, delete and download again",
        "status_not_downloaded" to "✗ Not downloaded yet",
        "status_no_models" to "No built-in Vosk models downloaded yet.\nChoose a model from the dropdown and click 'Download Model'.",
        "status_installed_header" to "Installed Vosk models (%d):\n",
        "status_selected_header" to "Selected model: %s\nStatus: %s\n\n"
    )

    private val arabicStrings = mapOf(
        "dialog_title" to "تنزيل نموذج Vosk",
        "model_label" to "النموذج: %s",
        "source_label" to "المصدر: alphacephei.com (تخزين محلي بدون إنترنت)",
        "connecting" to "جارٍ الاتصال بالخادم...",
        "pause" to "إيقاف مؤقت",
        "resume" to "استئناف",
        "cancel" to "إلغاء",
        "close" to "إغلاق",
        "resuming" to "جارٍ استئناف التنزيل...",
        "cancelling" to "جارٍ الإلغاء...",
        "paused_stats" to "متوقف عند %s / %s (%d%%). انقر استئناف للمتابعة.",
        "extracting" to "جارٍ فك ضغط ملفات النموذج في التخزين المحلي...",
        "done" to "اكتمل التنزيل! تم تثبيت النموذج بنجاح.",
        "error" to "خطأ: %s",
        "remaining_sec" to "متبقي %d ثانية",
        "remaining_min_sec" to "متبقي %d دقيقة و %d ثانية",
        "disk_space_error" to "لا توجد مساحة كافية على القرص لتنزيل وفك ضغط '%s'. المطلوب: ~%s، المتوفر: %s.",
        "installed_marker" to "%s ✓ (منصّب)",
        "download_model_button" to "تنزيل النموذج",
        "downloading_model_label" to "جارٍ التنزيل (%s)...",
        "test_success" to "نجح اختبار الاتصال.",
        "test_key_rejected" to "تم رفض مفتاح API من قبل الخادم.",
        "test_model_unknown" to "النموذج '%s' غير معروف لدى الخادم.",
        "test_model_not_audio" to "النموذج '%s' لا يقبل المدخلات الصوتية.",
        "test_server_unreachable" to "تعذر الوصول إلى الخادم: %s",
        "deepgram_unsupported_language" to "نموذج Deepgram '%s' لا يدعم اللغة '%s'.",
        "deepgram_unsupported_multi" to "نموذج Deepgram '%s' لا يدعم الكشف التلقائي المتعدد للغات.",
        "import_chooser_title" to "استيراد نموذج Vosk (ملف ZIP أو مجلد)",
        "import_cancelled" to "تم إلغاء استيراد النموذج.",
        "import_success" to "تم استيراد النموذج '%s' بنجاح إلى التخزين المحلي.",
        "import_failed" to "فشل الاستيراد: %s",
        "delete_unknown_model" to "نموذج غير معروف '%s'.",
        "delete_not_installed" to "النموذج '%s' غير منزّل على هذا الجهاز.",
        "delete_success" to "تم حذف النموذج '%s' بنجاح من جهازك.",
        "delete_failed" to "فشل حذف النموذج '%s'. يرجى التحقق من أذونات الملفات.",
        "status_installed" to "✓ منصّب وجاهز للاستخدام",
        "status_incomplete" to "غير مكتمل، يرجى الحذف وإعادة التنزيل",
        "status_not_downloaded" to "✗ لم يتم تنزيله بعد",
        "status_no_models" to "لم يتم تنزيل أي نماذج Vosk مدمجة بعد.\nاختر نموذجاً من القائمة وانقر 'تنزيل النموذج'.",
        "status_installed_header" to "نماذج Vosk المنصّبة (%d):\n",
        "status_selected_header" to "النموذج المختار: %s\nالحالة: %s\n\n"
    )

    fun get(key: String): String {
        val lang = Locale.getDefault().language.lowercase()
        val strings = if (lang == "ar") arabicStrings else englishStrings
        return strings[key] ?: englishStrings[key] ?: key
    }

    fun get(key: String, vararg args: Any): String =
        String.format(Locale.getDefault(), get(key), *args)
}

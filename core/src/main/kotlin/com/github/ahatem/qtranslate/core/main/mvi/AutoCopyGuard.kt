package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationCompletion
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation

/**
 * Chooses whether a completed translation should be auto-copied.
 *
 * Instant translation is excluded in every mode: it runs while the user is still typing, so
 * copying it would replace the clipboard each time they pause.
 */
internal object AutoCopyGuard {

    /**
     * @param translationStillCurrent whether [completion] still owns the translation request.
     *   Checked again here because a newer request can take ownership while this runs.
     * @return the text to copy, or null to leave the clipboard alone.
     */
    fun textToCopy(
        setting: AutoCopyTranslation,
        origin: TranslationOrigin,
        completion: TranslationCompletion?,
        translationStillCurrent: Boolean
    ): String? {
        if (!origin.isAutoCopied(setting)) return null
        // A null completion is a failed, blank, cancelled or superseded translation.
        if (completion == null || !translationStillCurrent) return null
        return completion.translatedText.takeIf { it.isNotBlank() }
    }

    private fun TranslationOrigin.isAutoCopied(setting: AutoCopyTranslation): Boolean =
        when (setting) {
            AutoCopyTranslation.OFF -> false
            AutoCopyTranslation.QUICK_TRANSLATE_ONLY -> this == TranslationOrigin.QUICK
            AutoCopyTranslation.ALL ->
                this == TranslationOrigin.MAIN ||
                    this == TranslationOrigin.QUICK ||
                    this == TranslationOrigin.OCR
        }
}

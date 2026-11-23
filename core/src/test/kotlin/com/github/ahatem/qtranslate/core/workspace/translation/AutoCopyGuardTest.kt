package com.github.ahatem.qtranslate.core.workspace.translation

import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Auto-copy is a decision about a finished translation, and every combination of setting, origin
 * and outcome has to hold.
 */
class AutoCopyGuardTest {

    private fun completion(text: String = "bonjour", requestId: Long = 1L) =
        TranslationCompletion(requestId, text)

    private fun copy(
        setting: AutoCopyTranslation,
        origin: TranslationOrigin,
        completion: TranslationCompletion? = completion(),
        current: Boolean = true
    ) = AutoCopyGuard.textToCopy(setting, origin, completion, current)

    @Test
    fun `the setting decides which origins are copied`() {
        // Typing (INSTANT) and internal work are never copied; ALL covers every explicit translation.
        val copied = mapOf(
            AutoCopyTranslation.OFF to emptySet(),
            AutoCopyTranslation.QUICK_TRANSLATE_ONLY to setOf(TranslationOrigin.QUICK),
            AutoCopyTranslation.ALL to setOf(TranslationOrigin.MAIN, TranslationOrigin.QUICK, TranslationOrigin.OCR)
        )
        for (setting in AutoCopyTranslation.entries) {
            for (origin in TranslationOrigin.entries) {
                val expected = if (origin in copied.getValue(setting)) "bonjour" else null
                assertEquals(expected, copy(setting, origin), "$origin under $setting")
            }
        }
    }

    @Test
    fun `a result that did not finish, lost its request or is blank is never copied`() {
        // A failed, cancelled or superseded request arrives as no completion at all. The current
        // check is the last gate before the side effect, so it holds for every mode and origin.
        val cases = mapOf(
            "no completion" to { setting: AutoCopyTranslation, origin: TranslationOrigin ->
                copy(setting, origin, completion = null)
            },
            "stale request" to { setting, origin -> copy(setting, origin, current = false) },
            "empty result" to { setting, origin -> copy(setting, origin, completion = completion("")) },
            "blank result" to { setting, origin -> copy(setting, origin, completion = completion("   ")) }
        )
        for ((label, attempt) in cases) {
            for (setting in AutoCopyTranslation.entries) {
                for (origin in TranslationOrigin.entries) {
                    assertNull(attempt(setting, origin), "$label: $origin under $setting")
                }
            }
        }
    }

    @Test
    fun `the result is copied verbatim rather than trimmed`() {
        val spaced = "  bonjour  "
        assertEquals(spaced, copy(AutoCopyTranslation.ALL, TranslationOrigin.MAIN, completion = completion(spaced)))
    }
}

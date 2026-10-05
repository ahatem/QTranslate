package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationCompletion
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

    // ---- OFF ----

    @Test
    fun `off copies nothing at all`() {
        for (origin in TranslationOrigin.entries) {
            assertNull(copy(AutoCopyTranslation.OFF, origin), "$origin must not be copied when the setting is off")
        }
    }

    // ---- QUICK_TRANSLATE_ONLY ----

    @Test
    fun `quick only copies a quick result`() {
        assertEquals("bonjour", copy(AutoCopyTranslation.QUICK_TRANSLATE_ONLY, TranslationOrigin.QUICK))
    }

    @Test
    fun `quick only leaves the main window alone`() {
        assertNull(copy(AutoCopyTranslation.QUICK_TRANSLATE_ONLY, TranslationOrigin.MAIN))
    }

    @Test
    fun `quick only leaves OCR alone`() {
        assertNull(copy(AutoCopyTranslation.QUICK_TRANSLATE_ONLY, TranslationOrigin.OCR))
    }

    @Test
    fun `quick only leaves typing and internal work alone`() {
        assertNull(copy(AutoCopyTranslation.QUICK_TRANSLATE_ONLY, TranslationOrigin.INSTANT))
        assertNull(copy(AutoCopyTranslation.QUICK_TRANSLATE_ONLY, TranslationOrigin.INTERNAL))
    }

    // ---- ALL ----

    @Test
    fun `all copies every explicit single-result translation`() {
        for (origin in listOf(TranslationOrigin.MAIN, TranslationOrigin.QUICK, TranslationOrigin.OCR)) {
            assertEquals("bonjour", copy(AutoCopyTranslation.ALL, origin), "$origin is an explicit translation")
        }
    }

    @Test
    fun `all still leaves instant translation alone`() {
        assertNull(copy(AutoCopyTranslation.ALL, TranslationOrigin.INSTANT))
    }

    @Test
    fun `all still leaves internal work alone`() {
        assertNull(copy(AutoCopyTranslation.ALL, TranslationOrigin.INTERNAL))
    }

    // ---- outcomes that must never reach the clipboard ----

    @Test
    fun `a translation that did not finish copies nothing`() {
        // A failed, cancelled or superseded request arrives here as no completion at all.
        assertNull(copy(AutoCopyTranslation.ALL, TranslationOrigin.MAIN, completion = null))
    }

    @Test
    fun `a result that lost its request copies nothing`() {
        assertNull(
            copy(AutoCopyTranslation.ALL, TranslationOrigin.MAIN, completion = completion(), current = false),
            "a newer request already owns the translation, so this one is stale"
        )
    }

    @Test
    fun `a blank result copies nothing`() {
        assertNull(copy(AutoCopyTranslation.ALL, TranslationOrigin.MAIN, completion = completion("")))
        assertNull(copy(AutoCopyTranslation.ALL, TranslationOrigin.MAIN, completion = completion("   ")))
    }

    @Test
    fun `the result is copied verbatim rather than trimmed`() {
        val spaced = "  bonjour  "
        assertEquals(spaced, copy(AutoCopyTranslation.ALL, TranslationOrigin.MAIN, completion = completion(spaced)))
    }

    @Test
    fun `every mode refuses a stale completion regardless of origin`() {
        // The current check is the last gate before the side effect, so it holds everywhere.
        for (setting in AutoCopyTranslation.entries) {
            for (origin in TranslationOrigin.entries) {
                assertNull(
                    copy(setting, origin, completion = completion(), current = false),
                    "$origin under $setting must not copy a result it no longer owns"
                )
            }
        }
    }

    @Test
    fun `every mode refuses a blank completion regardless of origin`() {
        for (setting in AutoCopyTranslation.entries) {
            for (origin in TranslationOrigin.entries) {
                assertNull(
                    copy(setting, origin, completion = completion("")),
                    "$origin under $setting must not copy an empty result"
                )
            }
        }
    }
}

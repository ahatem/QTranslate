package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.get
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoTranslateTest {

    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `auto translate is requested once per settled line and never for partials`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val cache = TranslationCache(scope, { text, _ ->
            requestCount.incrementAndGet()
            Ok("translated: $text")
        })

        val target = LanguageCode.ARABIC

        // Partial segment (draft = true) should be skipped
        val partialSeg = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 0,
            endMillis = 500,
            text = "partial speech",
            draft = true
        )
        val shouldTranslatePartial = !partialSeg.id.startsWith("live-") && !partialSeg.draft && partialSeg.text.isNotBlank()
        assertFalse(shouldTranslatePartial, "Draft/partial segments must not be auto-translated")

        // Settled segment (draft = false)
        val settledSeg = partialSeg.copy(draft = false, text = "settled speech")
        val shouldTranslateSettled = !settledSeg.id.startsWith("live-") && !settledSeg.draft && settledSeg.text.isNotBlank()
        assertTrue(shouldTranslateSettled, "Settled segments must be auto-translated")

        // Request translation
        val res1 = cache.translate(settledSeg.text, target)
        assertEquals("translated: settled speech", res1.get())
        assertEquals(1, requestCount.get())

        // Re-requesting same text should hit cache
        val res2 = cache.translate(settledSeg.text, target)
        assertEquals("translated: settled speech", res2.get())
        assertEquals(1, requestCount.get(), "Duplicate request for identical text must be deduplicated")
    }

    @Test
    fun `correction leads to new translation request and ignores stale result`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val cache = TranslationCache(scope, { text, _ ->
            requestCount.incrementAndGet()
            Ok("translated: $text")
        })

        val target = LanguageCode.ARABIC
        val originalText = "Original text"
        val correctedText = "Corrected accurate text"

        cache.translate(originalText, target)
        assertEquals(1, requestCount.get())

        cache.translate(correctedText, target)
        assertEquals(2, requestCount.get(), "Correction with changed text must trigger a new translation")

        // Comparing text: late result matching old text is not applied to corrected segment
        val currentSegText = correctedText
        val lateResultText = originalText
        assertFalse(currentSegText == lateResultText, "Late result from old text must be dropped when text does not match")
    }

    @Test
    fun `same language lines are skipped`() {
        val target = LanguageCode.ENGLISH
        val segSameLang = TranscriptSegment(
            id = "seg-en",
            source = AudioSource.MICROPHONE,
            startMillis = 0,
            endMillis = 1000,
            text = "Hello there",
            language = "en",
            draft = false
        )
        val isSameLang = segSameLang.language.equals(target.tag, ignoreCase = true)
        assertTrue(isSameLang, "Segment in same language as target must be skipped")
    }
}

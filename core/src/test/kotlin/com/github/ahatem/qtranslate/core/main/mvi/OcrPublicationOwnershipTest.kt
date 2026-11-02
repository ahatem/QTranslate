package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.core.settings.data.OcrPostSelectionAction
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Deterministic ownership races for screen OCR. Gates hold one request exactly after it returned
 * from the provider but before it is allowed to publish, then let a newer workflow take ownership.
 * No sleeps and no cooperative-cancellation assumptions: the old request is already runnable.
 */
class OcrPublicationOwnershipTest {

    @Test
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    fun `older OCR cleanup cannot cancel the translation installed by a newer claim`() = runBlocking {
        val fixture = OcrWorkflowFixture(TestScope(), dispatcher = Dispatchers.IO)
        val oldStarted = CompletableDeferred<Unit>()
        val aClaimed = CountDownLatch(1)
        val resumeA = java.util.concurrent.Semaphore(0)
        val bStarted = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        var cleanup: Thread? = null
        try {
            fixture.ocr.responder = { request ->
                if (request.image.width == 1) {
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                        .invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                            aClaimed.countDown()
                            resumeA.acquireUninterruptibly()
                        }
                    oldStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
                Ok(OCRResponse("B source"))
            }
            fixture.translator.beforeTranslate = { text ->
                if (text == "B source") { bStarted.complete(Unit); releaseB.await() }
            }
            fixture.store.dispatch(MainIntent.OcrWorkflowImage(ImageData(byteArrayOf(1), "png", 1, 1), OcrPostSelectionAction.COPY_TEXT))
            withTimeout(5_000) { oldStarted.await() }
            cleanup = Thread {
                fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.COPY_IMAGE))
            }.also { it.start() }
            assertTrue(aClaimed.await(5, TimeUnit.SECONDS), "A must pause after claiming, during detached-job cancellation")
            fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.TRANSLATE, OcrTranslationDestination.QUICK_TRANSLATE))
            withTimeout(5_000) { bStarted.await() }
            resumeA.release()
            cleanup.join(5_000)
            assertTrue(!cleanup.isAlive)
            assertTrue(fixture.store.state.value.isLoading, "older A must not cancel current B")
            releaseB.complete(Unit)
            val result = withTimeout(5_000) { fixture.store.state.first { it.translatedText == "t:B source" } }
            assertEquals("B source", result.inputText)
        } finally {
            resumeA.release()
            releaseB.complete(Unit)
            cleanup?.join(5_000)
            fixture.cleanUp()
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    fun `manual translation started during OCR cancellation keeps its popup and loading state`() = runBlocking {
        val fixture = OcrWorkflowFixture(TestScope(), dispatcher = Dispatchers.IO)
        val ocrStarted = CompletableDeferred<Unit>()
        val cancellationEntered = CountDownLatch(1)
        val resumeCancellation = java.util.concurrent.Semaphore(0)
        val manualStarted = CompletableDeferred<Unit>()
        val releaseManual = CompletableDeferred<Unit>()
        var cleanup: Thread? = null
        try {
            fixture.ocr.responder = { Ok(OCRResponse("OCR source")) }
            fixture.translator.beforeTranslate = { text ->
                if (text == "OCR source") {
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                        .invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                            cancellationEntered.countDown()
                            resumeCancellation.acquireUninterruptibly()
                        }
                    ocrStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                } else if (text == "manual source") {
                    manualStarted.complete(Unit)
                    releaseManual.await()
                }
            }
            fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.TRANSLATE, OcrTranslationDestination.QUICK_TRANSLATE))
            withTimeout(5_000) { ocrStarted.await() }
            cleanup = Thread { fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.COPY_IMAGE)) }.also { it.start() }
            assertTrue(cancellationEntered.await(5, TimeUnit.SECONDS))
            fixture.store.dispatch(MainIntent.ShowQuickTranslate("manual source"))
            withTimeout(5_000) { manualStarted.await() }
            resumeCancellation.release()
            cleanup.join(5_000)
            assertTrue(!cleanup.isAlive)
            assertTrue(fixture.store.state.value.isLoading, "OCR cleanup must not lower the manual spinner")
            assertTrue(fixture.store.state.value.isQuickTranslateDialogVisible, "OCR cleanup must not hide the manual popup")
            releaseManual.complete(Unit)
            withTimeout(5_000) { fixture.store.state.first { it.translatedText == "t:manual source" } }
            Unit
        } finally {
            resumeCancellation.release()
            releaseManual.complete(Unit)
            cleanup?.join(5_000)
            fixture.cleanUp()
        }
    }

    @Test
    fun `a stale copy-text workflow cannot emit a clipboard event`() = runBlocking {
        val fixture = OcrWorkflowFixture(
            testScope = TestScope(),
            dispatcher = Dispatchers.IO,
        )
        try {
            val firstAtPublication = CountDownLatch(1)
            val releaseFirst = java.util.concurrent.Semaphore(0)
            val firstAttemptFinished = CountDownLatch(1)
            val firstToken = AtomicLong(-1)
            fixture.ocr.responder = { Ok(OCRResponse("STALE_A")) }
            fixture.store.beforeOcrPublication = { token ->
                if (firstToken.compareAndSet(-1L, token)) {
                    firstAtPublication.countDown()
                    releaseFirst.acquireUninterruptibly()
                }
            }
            fixture.store.afterOcrPublication = { token ->
                if (token == firstToken.get()) firstAttemptFinished.countDown()
            }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(1), "png", 8, 8),
                    action = OcrPostSelectionAction.COPY_TEXT,
                )
            )
            assertTrue(firstAtPublication.await(5, TimeUnit.SECONDS))
            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(2), "png", 9, 9),
                    action = OcrPostSelectionAction.COPY_IMAGE,
                )
            )
            releaseFirst.release()
            assertTrue(firstAttemptFinished.await(5, TimeUnit.SECONDS))
            fixture.awaitEvent { it is MainEvent.CopyImageToClipboard }

            assertTrue(
                fixture.events.none { it == MainEvent.CopyToClipboard("STALE_A") },
                "a superseded copy-text workflow must not reach the clipboard",
            )
            assertTrue(
                fixture.events.any { it is MainEvent.CopyImageToClipboard },
                "the newer copy-image workflow still publishes its own event",
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a later OCR claim does not cancel a manual translation that replaced the OCR one`() = runBlocking {
        val fixture = OcrWorkflowFixture(
            testScope = TestScope(),
            dispatcher = Dispatchers.IO,
        )
        try {
            val ocrStarted = CompletableDeferred<Unit>()
            val releaseOcr = CompletableDeferred<Unit>()
            val manualStarted = CompletableDeferred<Unit>()
            val releaseManual = CompletableDeferred<Unit>()
            fixture.translator.beforeTranslate = { text ->
                when (text) {
                    "A" -> {
                        ocrStarted.complete(Unit)
                        releaseOcr.await()
                    }
                    "manual" -> {
                        manualStarted.complete(Unit)
                        releaseManual.await()
                    }
                }
            }
            fixture.ocr.responder = { Ok(OCRResponse("A")) }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(1), "png", 8, 8),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            withTimeout(5_000) { ocrStarted.await() }

            // The user replaces the OCR-owned Quick translation with a manual one. The manual
            // request now owns translation publication, even though OCR still remembers its own.
            fixture.store.dispatch(MainIntent.ShowQuickTranslate("manual"))
            withTimeout(5_000) { manualStarted.await() }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(2), "png", 9, 9),
                    action = OcrPostSelectionAction.COPY_IMAGE,
                )
            )
            assertTrue(
                fixture.store.state.value.isLoading,
                "a new OCR capture must not cancel an unrelated manual translation",
            )

            releaseManual.complete(Unit)
            val finished = withTimeout(5_000) {
                fixture.store.state.first { it.translatedText == "t:manual" && !it.isLoading }
            }
            assertEquals("manual", finished.inputText)
            releaseOcr.complete(Unit)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a newer capture cancels the OCR main translation and clears its loading flag`() = runBlocking {
        val fixture = OcrWorkflowFixture(
            testScope = TestScope(),
            ocrSettings = OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.MAIN_WINDOW,
            ),
            dispatcher = Dispatchers.IO,
        )
        try {
            val translationStarted = CompletableDeferred<Unit>()
            val releaseTranslation = CompletableDeferred<Unit>()
            val translationReturned = CountDownLatch(1)
            fixture.translator.beforeTranslate = { text ->
                if (text == "A") {
                    translationStarted.complete(Unit)
                    try {
                        releaseTranslation.await()
                    } finally {
                        translationReturned.countDown()
                    }
                }
            }
            fixture.ocr.responder = { Ok(OCRResponse("A")) }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(1), "png", 8, 8),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            withTimeout(5_000) { translationStarted.await() }
            assertTrue(fixture.store.state.value.isLoading)

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(2), "png", 9, 9),
                    action = OcrPostSelectionAction.COPY_IMAGE,
                )
            )
            assertEquals(false, fixture.store.state.value.isLoading, "cancelling OCR's translation clears its spinner")

            releaseTranslation.complete(Unit)
            assertTrue(translationReturned.await(5, TimeUnit.SECONDS))
            assertEquals("", fixture.store.state.value.translatedText)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a newer capture cancels the OCR quick translation it started`() = runBlocking {
        val fixture = OcrWorkflowFixture(
            testScope = TestScope(),
            ocrSettings = OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.QUICK_TRANSLATE,
            ),
            dispatcher = Dispatchers.IO,
        )
        try {
            val translationStarted = CompletableDeferred<Unit>()
            val releaseTranslation = CompletableDeferred<Unit>()
            val translationReturned = CountDownLatch(1)
            fixture.translator.beforeTranslate = { text ->
                if (text == "A") {
                    translationStarted.complete(Unit)
                    try {
                        releaseTranslation.await()
                    } finally {
                        translationReturned.countDown()
                    }
                }
            }
            fixture.ocr.responder = { Ok(OCRResponse("A")) }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(1), "png", 8, 8),
                    action = OcrPostSelectionAction.TRANSLATE,
                )
            )
            withTimeout(5_000) { translationStarted.await() }
            assertTrue(fixture.store.state.value.isLoading)
            assertTrue(fixture.store.state.value.isQuickTranslateDialogVisible)

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(2), "png", 9, 9),
                    action = OcrPostSelectionAction.COPY_IMAGE,
                )
            )
            assertEquals(false, fixture.store.state.value.isLoading)

            releaseTranslation.complete(Unit)
            assertTrue(translationReturned.await(5, TimeUnit.SECONDS))
            assertEquals("", fixture.store.state.value.translatedText)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a stale main OCR result cannot publish after a newer workflow owns OCR`() = runBlocking {
        val fixture = OcrWorkflowFixture(
            testScope = TestScope(),
            ocrSettings = OcrSettings(
                postSelectionAction = OcrPostSelectionAction.TRANSLATE,
                translationDestination = OcrTranslationDestination.MAIN_WINDOW,
            ),
            dispatcher = Dispatchers.IO,
        )
        try {
            val firstAtPublication = CountDownLatch(1)
            val releaseFirst = java.util.concurrent.Semaphore(0)
            val firstAttemptFinished = CountDownLatch(1)
            val releaseSecond = java.util.concurrent.Semaphore(0)
            val firstToken = AtomicLong(-1)

            fixture.ocr.responder = { request ->
                if (request.image.width == 8) {
                    Ok(OCRResponse("STALE_A"))
                } else {
                    releaseSecond.acquireUninterruptibly()
                    Ok(OCRResponse("CURRENT_B"))
                }
            }
            fixture.store.beforeOcrPublication = { token ->
                if (firstToken.compareAndSet(-1L, token)) {
                    firstAtPublication.countDown()
                    releaseFirst.acquireUninterruptibly()
                }
            }
            fixture.store.afterOcrPublication = { token ->
                if (token == firstToken.get()) firstAttemptFinished.countDown()
            }

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(1), "png", 8, 8),
                    action = OcrPostSelectionAction.TRANSLATE,
                    destinationOverride = OcrTranslationDestination.MAIN_WINDOW,
                )
            )
            assertTrue(firstAtPublication.await(5, TimeUnit.SECONDS), "request A never reached publication")

            fixture.store.dispatch(
                MainIntent.OcrWorkflowImage(
                    image = ImageData(byteArrayOf(2), "png", 9, 9),
                    action = OcrPostSelectionAction.TRANSLATE,
                    destinationOverride = OcrTranslationDestination.MAIN_WINDOW,
                )
            )

            releaseFirst.release()
            assertTrue(firstAttemptFinished.await(5, TimeUnit.SECONDS), "request A never finished publishing")

            assertNotEquals("STALE_A", fixture.store.state.value.inputText)
            assertEquals("", fixture.store.state.value.inputText)

            releaseSecond.release()
            val finished = withTimeout(5_000) {
                fixture.store.state.first { it.translatedText == "t:CURRENT_B" && !it.isLoading }
            }
            assertEquals("CURRENT_B", finished.inputText)
            assertEquals(listOf("CURRENT_B"), fixture.translator.received)
        } finally {
            fixture.cleanUp()
        }
    }
}

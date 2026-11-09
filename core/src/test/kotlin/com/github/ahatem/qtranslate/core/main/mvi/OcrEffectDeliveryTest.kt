package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.core.settings.data.*
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.*

class OcrEffectDeliveryTest {
    @Test
    fun `OCR clipboard effect rejects supersession while waiting for Swing acceptance`() = runBlocking {
        val fixture = OcrWorkflowFixture(TestScope(), dispatcher = Dispatchers.IO, collectEvents = false)
        val atPublication = CountDownLatch(1)
        val releasePublication = Semaphore(0)
        val edtBlocked = CountDownLatch(1)
        val allowB = CountDownLatch(1)
        val dequeued = CountDownLatch(1)
        val finishedB = CountDownLatch(1)
        try {
            fixture.ocr.responder = { Ok(OCRResponse("stale A")) }
            fixture.store.beforeOcrPublication = { atPublication.countDown(); releasePublication.acquireUninterruptibly() }
            val effect = async(Dispatchers.IO) {
                val delivery = fixture.store.eventDeliveries.first { it.event is MainEvent.CopyToClipboard }
                dequeued.countDown()
                withContext(Dispatchers.Swing) { delivery.accept() }
            }
            fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.COPY_TEXT))
            assertTrue(atPublication.await(5, TimeUnit.SECONDS))
            SwingUtilities.invokeLater {
                edtBlocked.countDown()
                check(allowB.await(5, TimeUnit.SECONDS))
                fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.OPEN_WORKSPACE))
                finishedB.countDown()
            }
            assertTrue(edtBlocked.await(5, TimeUnit.SECONDS))
            releasePublication.release()
            assertTrue(dequeued.await(5, TimeUnit.SECONDS), "A must cross the queue before B claims")
            allowB.countDown()
            assertTrue(finishedB.await(5, TimeUnit.SECONDS))
            assertNull(withTimeout(5_000) { effect.await() }, "A must not be accepted after B owns OCR")
        } finally {
            releasePublication.release()
            allowB.countDown()
            fixture.cleanUp()
        }
    }

    @Test
    fun `translation auto-copy retains its identity until delayed delivery`() = runBlocking {
        val fixture = OcrWorkflowFixture(TestScope(), autoCopy = AutoCopyTranslation.ALL, dispatcher = Dispatchers.IO, collectEvents = false)
        try {
            fixture.ocr.responder = { Ok(OCRResponse("source A")) }
            val pending = async(Dispatchers.IO) {
                fixture.store.eventDeliveries.first { it.event is MainEvent.CopyToClipboard }
            }
            fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.TRANSLATE, OcrTranslationDestination.QUICK_TRANSLATE))
            val copy = withTimeout(5_000) { pending.await() }
            assertEquals(MainEvent.CopyToClipboard("t:source A"), copy.event)
            fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.OPEN_WORKSPACE))
            assertNull(withContext(Dispatchers.Swing) { copy.accept() }, "queued auto-copy must expire with its translation")
        } finally { fixture.cleanUp() }
    }

    @Test
    fun `accepted effects are one shot and do not keep ownership locked during delivery`() = runBlocking {
        val fixture = OcrWorkflowFixture(TestScope(), dispatcher = Dispatchers.IO, collectEvents = false)
        try {
            val pending = async(Dispatchers.IO) { fixture.store.eventDeliveries.first { it.event is MainEvent.CopyImageToClipboard } }
            fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.COPY_IMAGE))
            val delivery = withTimeout(5_000) { pending.await() }
            withContext(Dispatchers.Swing) {
                assertNotNull(delivery.accept())
                // Actual UI work is outside acceptance. Another thread must be able to claim OCR.
                val claimed = CountDownLatch(1)
                val worker = Thread {
                    fixture.store.dispatch(MainIntent.OcrWorkflowImage(fixture.image(), OcrPostSelectionAction.OPEN_WORKSPACE))
                    claimed.countDown()
                }.also { it.start() }
                assertTrue(claimed.await(5, TimeUnit.SECONDS))
                worker.join(5_000)
                assertNull(delivery.accept(), "even an accepted envelope cannot deliver twice")
            }
        } finally { fixture.cleanUp() }
    }
}

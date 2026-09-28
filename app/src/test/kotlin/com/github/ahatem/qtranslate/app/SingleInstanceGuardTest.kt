package com.github.ahatem.qtranslate.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceGuardTest {
    @Test
    fun `second instance sends focus request and exits`() {
        val focused = CountDownLatch(1)
        assertTrue(SingleInstanceGuard.tryLock { focused.countDown() })
        try {
            assertFalse(SingleInstanceGuard.tryLock { })
            assertTrue(focused.await(5, TimeUnit.SECONDS))
        } finally {
            SingleInstanceGuard.release()
        }
    }
}

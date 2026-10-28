package com.github.ahatem.qtranslate.plugins.speech

import kotlin.test.Test

/** Verifies that the native Vosk library initializes successfully. */
class VoskMessageTest {

    @Test
    fun `vosk native library loads successfully`() {
        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
    }
}


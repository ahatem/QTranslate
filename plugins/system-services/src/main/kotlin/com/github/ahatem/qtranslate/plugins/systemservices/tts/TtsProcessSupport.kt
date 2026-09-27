package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessOutcome
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

internal object TtsProcessSupport {
    fun checked(outcome: ProcessOutcome, engine: String): Result<Unit, ServiceError> = when {
        outcome.timedOut -> Err(ServiceError.TimeoutError(engine + " speech synthesis timed out."))
        outcome.exitCode != 0 -> Err(ServiceError.ServiceUnavailableError(
            engine + " speech synthesis failed (exit " + outcome.exitCode + ")."
        ))
        else -> Ok(Unit)
    }

    fun wav(bytes: ByteArray): Result<ByteArray, ServiceError> =
        if (bytes.size > 44 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
        ) Ok(bytes)
        else Err(ServiceError.InvalidResponseError("System speech engine returned empty or invalid WAV audio."))
}

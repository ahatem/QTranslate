package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessOutcome
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

internal object TtsProcessSupport {
    fun checked(outcome: ProcessOutcome, engine: String): Result<Unit, ServiceError> = when {
        outcome.timedOut -> Err(ServiceError.TimeoutError(engine + " speech synthesis timed out."))
        outcome.exitCode != 0 -> Err(ServiceError.ServiceUnavailableError(
            engine + " speech synthesis failed (exit " + outcome.exitCode + ")."
        ))
        else -> Ok(Unit)
    }

    fun wav(bytes: ByteArray): Result<ByteArray, ServiceError> {
        val valid = bytes.size > 44 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) &&
            runCatching {
                AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { stream ->
                    val format = stream.format
                    (format.encoding == AudioFormat.Encoding.PCM_SIGNED ||
                        format.encoding == AudioFormat.Encoding.PCM_UNSIGNED) &&
                        format.frameSize > 0 && stream.read(ByteArray(format.frameSize)) > 0
                }
            }.getOrDefault(false)
        return if (valid) Ok(bytes)
        else Err(ServiceError.InvalidResponseError("System speech engine returned empty, non-PCM, or invalid WAV audio."))
    }
}

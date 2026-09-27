package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.backend.TempFiles
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import java.io.File
import java.io.IOException

internal abstract class FileTtsBackend(protected val runner: ProcessRunner) : SystemTtsBackend {
    override suspend fun synthesize(text: String, voiceId: String, speed: Float): Result<ByteArray, ServiceError> {
        var input: File? = null
        var output: File? = null
        return try {
            input = TempFiles.write("qt-system-tts-input-", ".txt", text.toByteArray(Charsets.UTF_8))
            output = TempFiles.create("qt-system-tts-output-", ".wav")
            val source = input
            val target = output
            coroutineBinding {
                render(source, target, voiceId, speed).bind()
                TtsProcessSupport.wav(target.readBytes()).bind()
            }
        } catch (_: IOException) {
            Err(ServiceError.ServiceUnavailableError("System speech audio could not be written or read."))
        } finally {
            TempFiles.deleteQuietly(input, output)
        }
    }

    protected abstract suspend fun render(
        input: File, output: File, voiceId: String, speed: Float
    ): Result<Unit, ServiceError>
}

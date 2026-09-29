package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.backend.TempFiles
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import java.io.File

internal class MacTtsBackend(
    runner: ProcessRunner, private val say: String, private val afconvert: String
) : FileTtsBackend(runner) {
    override val displayName: String = "macOS say"

    override suspend fun discoverVoices(): Result<List<SystemVoice>, ServiceError> = coroutineBinding {
        val outcome = runner.run(listOf(say, "-v", "?"), 10_000L).bind()
        TtsProcessSupport.checked(outcome, displayName).bind()
        outcome.stdout.lineSequence().mapNotNull { line ->
            parseVoiceLine(line)
        }.distinctBy { it.id }.toList()
    }

    override suspend fun render(
        input: File, output: File, voiceId: String, speed: Float
    ): Result<Unit, ServiceError> {
        val intermediate = TempFiles.create("qt-system-tts-mac-", ".aiff")
        return try {
            coroutineBinding {
                val rate = (175 * speed).toInt().coerceIn(80, 450)
                val speech = runner.run(listOf(say, "-v", voiceId, "-r", rate.toString(),
                    "-f", input.absolutePath, "-o", intermediate.absolutePath), 60_000L).bind()
                TtsProcessSupport.checked(speech, displayName).bind()
                val convert = runner.run(listOf(afconvert, "-f", "WAVE", "-d", "LEI16",
                    intermediate.absolutePath, output.absolutePath), 20_000L).bind()
                TtsProcessSupport.checked(convert, "afconvert").bind()
            }
        } finally {
            TempFiles.deleteQuietly(intermediate)
        }
    }

    internal fun parseVoiceLine(line: String): SystemVoice? = VOICE_LINE.find(line)?.let { match ->
        val name = match.groupValues[1].trim()
        SystemVoice(name, name, match.groupValues[2].replace('_', '-'))
    }

    private companion object {
        private val VOICE_LINE = Regex("^(.+?)\\s+([A-Za-z]{2,8}(?:[_-](?:[A-Za-z]{4}|[A-Za-z]{2}|[0-9]{3}))*)\\s+#")
    }
}

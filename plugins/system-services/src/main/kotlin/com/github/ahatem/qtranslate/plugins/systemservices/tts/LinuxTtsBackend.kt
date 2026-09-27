package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.tts.Gender
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessRunner
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import java.io.File

internal class LinuxTtsBackend(runner: ProcessRunner, private val executable: String) : FileTtsBackend(runner) {
    override val displayName: String = "eSpeak"

    override suspend fun discoverVoices(): Result<List<SystemVoice>, ServiceError> = coroutineBinding {
        val outcome = runner.run(listOf(executable, "--voices"), 10_000L).bind()
        TtsProcessSupport.checked(outcome, displayName).bind()
        outcome.stdout.lineSequence().drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 6)
            if (fields.size < 5 || fields[0].toIntOrNull() == null) null
            else SystemVoice(fields[3], fields[3].replace('_', ' '), fields[1],
                when (fields[2].lastOrNull()?.uppercaseChar()) {
                    'M' -> Gender.MALE
                    'F' -> Gender.FEMALE
                    else -> null
                })
        }.distinctBy { it.id }.toList()
    }

    override suspend fun render(
        input: File, output: File, voiceId: String, speed: Float
    ): Result<Unit, ServiceError> = coroutineBinding {
        val rate = (175 * speed).toInt().coerceIn(80, 450)
        val outcome = runner.run(
            listOf(executable, "-v", voiceId, "-b", "1", "-s", rate.toString(),
                "-w", output.absolutePath, "-f", input.absolutePath), 60_000L
        ).bind()
        TtsProcessSupport.checked(outcome, displayName).bind()
    }
}

package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.tts.Gender
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.backend.TempFiles
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

internal class WindowsTtsBackend(
    runner: ProcessRunner, private val powershell: String, private val script: File
) : FileTtsBackend(runner) {
    override val displayName: String = "Windows SAPI"

    override suspend fun discoverVoices(): Result<List<SystemVoice>, ServiceError> {
        var output: File? = null
        return try {
            output = TempFiles.create("qt-system-tts-voices-", ".json")
            val target = output
            coroutineBinding {
                val outcome = runner.run(command("voices", target), DISCOVERY_TIMEOUT).bind()
                TtsProcessSupport.checked(outcome, displayName).bind()
                val records = try {
                    Json.decodeFromString<List<WindowsVoiceRecord>>(target.readText(Charsets.UTF_8))
                } catch (_: Exception) {
                    Err(ServiceError.InvalidResponseError("Windows speech returned an invalid voice list.")).bind()
                }
                records.map { SystemVoice(it.id, it.name, it.locale, it.gender.toGender()) }
            }
        } catch (_: IOException) {
            Err(ServiceError.ServiceUnavailableError("Windows speech voice list could not be read."))
        } finally {
            TempFiles.deleteQuietly(output)
        }
    }

    override suspend fun render(
        input: File, output: File, voiceId: String, speed: Float
    ): Result<Unit, ServiceError> = coroutineBinding {
        val outcome = runner.run(command("synthesize", output) +
            listOf("-InputPath", input.absolutePath, "-Voice", voiceId, "-Rate",
                ((speed - 1f) * 10f).toInt().coerceIn(-10, 10).toString()), SYNTHESIS_TIMEOUT).bind()
        TtsProcessSupport.checked(outcome, displayName).bind()
    }

    internal fun command(action: String, output: File): List<String> =
        listOf(powershell, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
            "-Command", action, "-OutputPath", output.absolutePath)

    private fun String.toGender(): Gender? = when (uppercase()) {
        "MALE" -> Gender.MALE
        "FEMALE" -> Gender.FEMALE
        "NEUTRAL" -> Gender.NEUTRAL
        else -> null
    }

    @Serializable
    private data class WindowsVoiceRecord(
        val id: String, val name: String, val locale: String, val gender: String = ""
    )

    private companion object {
        const val DISCOVERY_TIMEOUT = 10_000L
        const val SYNTHESIS_TIMEOUT = 60_000L
    }
}

package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.tts.Gender
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessOutcome
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
    override val displayName: String = "Windows WinRT"

    override suspend fun discoverVoices(): Result<List<SystemVoice>, ServiceError> {
        var output: File? = null
        return try {
            output = TempFiles.create("qt-system-tts-voices-", ".json")
            val target = output
            coroutineBinding {
                val outcome = runner.run(command("voices", target), DISCOVERY_TIMEOUT).bind()
                checked(outcome).bind()
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
                speed.coerceIn(0.5f, 6f).toString()), SYNTHESIS_TIMEOUT).bind()
        checked(outcome).bind()
    }

    private fun checked(outcome: ProcessOutcome): Result<Unit, ServiceError> {
        if (outcome.exitCode == 2 && !outcome.timedOut)
            return Err(ServiceError.InvalidInputError("The selected Windows voice is no longer installed."))
        if (outcome.exitCode == 3 && !outcome.timedOut)
            return Err(ServiceError.InvalidResponseError("Windows WinRT returned an unsupported audio format."))
        val nativeCode = Regex("HRESULT=0x[0-9A-Fa-f]{8}").find(outcome.stderr)?.value
        if (nativeCode != null && outcome.exitCode != 0 && !outcome.timedOut)
            return Err(ServiceError.ServiceUnavailableError("Windows WinRT speech synthesis failed ($nativeCode)."))
        return TtsProcessSupport.checked(outcome, displayName)
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

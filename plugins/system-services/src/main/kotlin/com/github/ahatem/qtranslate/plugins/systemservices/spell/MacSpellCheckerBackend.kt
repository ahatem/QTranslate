package com.github.ahatem.qtranslate.plugins.systemservices.spell

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.backend.TempFiles
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.coroutines.coroutineBinding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class MacSpellReply(
    val ok: Boolean,
    val languages: List<String> = emptyList(),
    val findings: List<MacSpellFinding> = emptyList(),
)

@Serializable
internal data class MacSpellFinding(
    val start: Int,
    val length: Int,
    val original: String,
    val suggestions: List<String> = emptyList(),
)

internal class MacSpellCheckerBackend(
    private val runner: ProcessRunner,
    private val helper: File?,
) : SystemSpellCheckerBackend {
    override val displayName = "macOS NSSpellChecker"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun languages(): Result<Set<String>, ServiceError> =
        request("languages", null, null).let { result ->
            result.fold({ Ok(it.languages.toSet()) }, { Err(it) })
        }

    override suspend fun check(text: String, languageTag: String): Result<List<SpellingFinding>, ServiceError> =
        request("check", text, languageTag).let { result ->
            result.fold(
                { Ok(it.findings.map { finding -> SpellingFinding(finding.start, finding.length, finding.suggestions, finding.original) }) },
                { Err(it) },
            )
        }

    private suspend fun request(command: String, text: String?, languageTag: String?): Result<MacSpellReply, ServiceError> = coroutineBinding {
        val executable = helper?.takeIf { it.isFile && it.canExecute() }
            ?: Err(ServiceError.ServiceUnavailableError("The packaged macOS spell checker is unavailable.")).bind()
        val input = text?.let { TempFiles.write("qt-spell-input-", ".txt", it.toByteArray(Charsets.UTF_8)) }
        val output = TempFiles.create("qt-spell-output-", ".json")
        try {
            val args = mutableListOf(executable.absolutePath, "--command", command, "--output", output.absolutePath)
            if (input != null) args += listOf("--input", input.absolutePath)
            if (languageTag != null) args += listOf("--language", languageTag)
            val outcome = runner.run(args, 10_000L).bind()
            if (outcome.timedOut) Err(ServiceError.TimeoutError("The macOS spell checker timed out.")).bind()
            if (outcome.exitCode != 0 || !output.isFile || output.length() == 0L) {
                Err(ServiceError.ServiceUnavailableError("The macOS spell checker failed.")).bind()
            }
            val parsed = runCatching { json.decodeFromString<MacSpellReply>(output.readText()) }.getOrNull()
                ?: Err(ServiceError.InvalidResponseError("The macOS spell checker returned an unreadable result.")).bind()
            if (!parsed.ok) Err(ServiceError.ServiceUnavailableError("The macOS spell checker could not process the request.")).bind()
            parsed
        } finally {
            TempFiles.deleteQuietly(input, output)
        }
    }
}

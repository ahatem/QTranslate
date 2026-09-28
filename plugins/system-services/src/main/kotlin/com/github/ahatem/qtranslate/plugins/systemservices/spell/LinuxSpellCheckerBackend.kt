package com.github.ahatem.qtranslate.plugins.systemservices.spell

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ExecutableLocator
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.backend.TempFiles
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.coroutines.coroutineBinding

/** Enchant uses installed desktop providers; Hunspell is the deterministic fallback. */
internal class LinuxSpellCheckerBackend private constructor(
    private val runner: ProcessRunner,
    private val executable: String,
    private val engine: Engine,
    private val dictionaries: Set<String>,
) : SystemSpellCheckerBackend {
    override val displayName = engine.name
    override suspend fun languages(): Result<Set<String>, ServiceError> = Ok(dictionaries)

    override suspend fun check(text: String, languageTag: String): Result<List<SpellingFinding>, ServiceError> = coroutineBinding {
        if (languageTag !in dictionaries) {
            Err(ServiceError.ServiceUnavailableError("The selected system dictionary is no longer available.")).bind()
        }
        val input = TempFiles.write("qt-spell-input-", ".txt", text.toByteArray(Charsets.UTF_8))
        try {
            val command = when (engine) {
                Engine.ENCHANT -> listOf(executable, "-a", "-d", languageTag, input.absolutePath)
                Engine.HUNSPELL -> listOf(executable, "-a", "-i", "UTF-8", "-d", languageTag, input.absolutePath)
            }
            val outcome = runner.run(command, 10_000L).bind()
            if (outcome.timedOut) Err(ServiceError.TimeoutError("The system spell checker timed out.")).bind()
            if (outcome.exitCode != 0) Err(ServiceError.ServiceUnavailableError("The system spell checker failed.")).bind()
            parse(text, outcome.stdout).bind()
        } finally {
            TempFiles.deleteQuietly(input)
        }
    }

    internal enum class Engine { ENCHANT, HUNSPELL }

    companion object {
        suspend fun discover(locator: ExecutableLocator, runner: ProcessRunner): Result<SystemSpellCheckerBackend, ServiceError> {
            val enchant = locator.locate("enchant-2")
            val list = locator.locate("enchant-lsmod-2")
            if (enchant != null && list != null) {
                val result = runner.run(listOf(list, "-list-dicts"), 5_000L)
                val tags = result.fold(
                    { outcome -> if (outcome.exitCode == 0 && !outcome.timedOut) parseEnchantDictionaries(outcome.stdout) else emptySet() },
                    { emptySet() },
                )
                if (tags.isNotEmpty()) return Ok(LinuxSpellCheckerBackend(runner, enchant, Engine.ENCHANT, tags))
            }
            val hunspell = locator.locate("hunspell")
            if (hunspell != null) {
                val result = runner.run(listOf(hunspell, "-D"), 5_000L)
                val tags = result.fold(
                    { outcome -> if (!outcome.timedOut) parseHunspellDictionaries(outcome.stdout + "\n" + outcome.stderr) else emptySet() },
                    { emptySet() },
                )
                if (tags.isNotEmpty()) return Ok(LinuxSpellCheckerBackend(runner, hunspell, Engine.HUNSPELL, tags))
            }
            return Err(ServiceError.ServiceUnavailableError("No installed Linux spell checker with dictionaries was found."))
        }

        internal fun parseEnchantDictionaries(raw: String): Set<String> = raw.lineSequence()
            .map { it.trim().substringBefore(' ').substringBefore('\t') }
            .filter { DICTIONARY_TAG.matches(it) }
            .toSet()

        internal fun parseHunspellDictionaries(raw: String): Set<String> = raw.lineSequence()
            .map { it.trim().substringAfterLast('/').substringAfterLast('\\').removeSuffix(".dic").removeSuffix(".aff") }
            .filter { DICTIONARY_TAG.matches(it) }
            .toSet()

        private val DICTIONARY_TAG = Regex("[a-z]{2,3}(?:[_-][A-Za-z0-9]{2,8})*")

        internal fun parse(text: String, output: String): Result<List<SpellingFinding>, ServiceError> {
            if (!output.startsWith("@(#)")) return Err(ServiceError.InvalidResponseError("The system spell checker returned an invalid header."))
            val sourceLines = text.split('\n')
            val reports = output.substringAfter('\n', "").trimStart('\r', '\n')
                .split(Regex("\\r?\\n\\r?\\n"))
            val findings = mutableListOf<SpellingFinding>()
            var lineStart = 0
            for ((lineIndex, rawLine) in sourceLines.withIndex()) {
                val source = rawLine.removeSuffix("\r")
                val report = reports.getOrNull(lineIndex).orEmpty()
                for (row in report.lineSequence()) {
                    val clean = row.trimEnd('\r')
                    if (clean.isEmpty() || clean.startsWith("*") || clean.startsWith("+") || clean.startsWith("-")) continue
                    val match = ISSUE.matchEntire(clean)
                        ?: return Err(ServiceError.InvalidResponseError("The system spell checker returned a malformed finding."))
                    val nativeWord = match.groupValues[2]
                    val oneBasedOffset = match.groupValues[4].toIntOrNull()
                        ?: return Err(ServiceError.InvalidResponseError("The system spell checker returned an invalid offset."))
                    val offset = sourceOffset(source, nativeWord, oneBasedOffset)
                        ?: return Err(ServiceError.InvalidResponseError("The system spell checker returned an unaligned offset."))
                    val suggestions = match.groupValues[5].takeIf { it.isNotBlank() }
                        ?.split(", ") ?: emptyList()
                    findings += SpellingFinding(lineStart + offset, nativeWord.length, suggestions, nativeWord)
                }
                lineStart += rawLine.length + 1
            }
            return Ok(findings)
        }

        /** Convert the engine's one-based character position; reject ambiguous Unicode layouts. */
        private fun sourceOffset(line: String, word: String, position: Int): Int? {
            val zero = position - 1
            if (zero < 0) return null
            val candidates = buildSet {
                if (zero <= line.length) add(zero)
                if (zero <= line.codePointCount(0, line.length)) add(line.offsetByCodePoints(0, zero))
                val bytes = line.toByteArray(Charsets.UTF_8)
                if (zero <= bytes.size) {
                    val prefix = bytes.copyOfRange(0, zero).toString(Charsets.UTF_8)
                    if (prefix.toByteArray(Charsets.UTF_8).size == zero) add(prefix.length)
                }
            }.filter { it + word.length <= line.length && line.regionMatches(it, word, 0, word.length) }
            return candidates.singleOrNull()
        }

        private val ISSUE = Regex("([&#]) (.+?) (?:(\\d+) )?(\\d+)(?:: (.*))?")
    }
}

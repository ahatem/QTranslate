package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.CorrectionType
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckResponse
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SpellLanguageMapper
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SpellingFinding
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SystemSpellCheckerBackend
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding

internal class SystemSpellCheckerService(
    private val backend: SystemSpellCheckerBackend,
    nativeLanguages: Set<String>,
    private val logger: Logger,
) : SpellChecker {
    private val mapper = SpellLanguageMapper(nativeLanguages)
    override val key = "system-spell-checker"
    override val name = "System Spell Checker"
    override val version = "1.0.0"
    override val iconPath = "assets/system-spell-icon.svg"
    override val metadata = ServiceMetadata(
        requiresConfiguration = false,
        isFree = true,
        notes = DisplayText.literal("Local, offline spelling using installed system dictionaries. No account or API key required."),
    )
    override val supportedLanguages = SupportedLanguages.Specific(mapper.supported)

    internal suspend fun close() = backend.close()

    override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> = coroutineBinding {
        val nativeTag = mapper.nativeTag(request.language) ?: Err(
            ServiceError.UnsupportedLanguageError(request.language, "No installed system spell checker supports this language.")
        ).bind()
        val started = System.nanoTime()
        val findings = backend.check(request.text, nativeTag).bind()
        val corrections = validate(request.text, findings).bind()
        val corrected = StringBuilder(request.text)
        for (correction in corrections.asReversed()) {
            correction.suggestions.firstOrNull()?.let { corrected.replace(correction.startIndex, correction.endIndex, it) }
        }
        logger.info("System Spell Checker (${backend.displayName}) checked ${request.text.length} characters as " +
            "${request.language.tag} in ${(System.nanoTime() - started) / 1_000_000}ms (${corrections.size} findings)")
        SpellCheckResponse(corrected.toString(), corrections)
    }

    private fun validate(text: String, findings: List<SpellingFinding>): Result<List<Correction>, ServiceError> {
        val ordered = findings.sortedWith(compareBy({ it.start }, { it.length }))
        var previousEnd = 0
        val corrections = ArrayList<Correction>(ordered.size)
        for (finding in ordered) {
            val end = finding.start.toLong() + finding.length
            if (finding.start < previousEnd || finding.start < 0 || finding.length <= 0 || end > text.length) {
                return Err(ServiceError.InvalidResponseError("The system spell checker returned an invalid source range."))
            }
            val original = text.substring(finding.start, end.toInt())
            if (finding.original != null && finding.original != original) {
                return Err(ServiceError.InvalidResponseError("The system spell checker returned a mismatched source range."))
            }
            corrections += Correction(original, finding.start, end.toInt(), finding.suggestions.distinct(), CorrectionType.SPELLING)
            previousEnd = end.toInt()
        }
        return Ok(corrections)
    }
}

package com.github.ahatem.qtranslate.plugins.csv

import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.plugin.ServiceCapability
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * Looks words up in the user's own CSV file.
 *
 * Deliberately a [Dictionary] rather than a new capability: the application already knows how to
 * offer a dictionary — a Ctrl+D popup, a docked panel, a context-menu entry, a picker in settings
 * — and a private glossary is a dictionary. Inventing a capability would have meant building all
 * of that again for the same interaction.
 */
class CsvDictionaryService(
    private val settings: () -> CsvSettings,
    private val index: () -> CsvIndex?
) : Dictionary {

    override val capabilities = setOf(ServiceCapability.DICTIONARY)

    override val key = "csv-dictionary"
    override val name = "CSV Dictionary"
    override val version = "1.0.0"
    override val iconPath = "assets/csv.svg"

    // The file's language is whatever the user put in it, and it is looked up locally either way.
    override val supportedLanguages = SupportedLanguages.All

    override val metadata = ServiceMetadata(
        requiresConfiguration = true,
        isFree = true,
        notes = com.github.ahatem.qtranslate.api.plugin.DisplayText(
            "csv.privacy_note",
            "Looks up terms in a local file. Nothing is sent anywhere."
        )
    )

    /**
     * Reports whether the file is usable, so the user finds out in settings rather than the first
     * time they press Ctrl+D on a word.
     */
    override suspend fun validate(): Result<Unit, ServiceError> {
        val path = settings().filePath
        if (path.isBlank()) {
            return Err(ServiceError.ConfigurationError("No CSV file chosen yet."))
        }
        val loaded = index()
            ?: return Err(ServiceError.ConfigurationError("Could not read '$path'."))
        if (loaded.size == 0) {
            return Err(
                ServiceError.ConfigurationError(
                    "Read '$path' but found no usable rows. Check the delimiter and column settings."
                )
            )
        }
        return Ok(Unit)
    }

    override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> {
        val loaded = index()
            ?: return Err(ServiceError.ConfigurationError("No CSV file loaded. Choose one in plugin settings."))

        val matches = loaded.lookup(request.word)

        // A miss is an empty result, not an error: the dictionary panel already knows how to say
        // "nothing found", and an error would put a red status bar in front of the user for the
        // ordinary case of a word not being in their own glossary.
        if (matches.isEmpty()) return Ok(DictionaryResponse(emptyList()))

        val label = settings().entryLabel.ifBlank { "term" }
        return Ok(
            DictionaryResponse(
                listOf(
                    DictionaryEntry(
                        word = matches.first().term,
                        partOfSpeech = label,
                        definitions = matches.map { row ->
                            Definition(text = row.definition, example = row.note)
                        }
                    )
                )
            )
        )
    }
}

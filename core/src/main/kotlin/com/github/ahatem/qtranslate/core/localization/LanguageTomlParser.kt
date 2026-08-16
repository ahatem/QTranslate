package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.core.Logger
import java.time.LocalDate

/**
 * A minimal TOML-subset parser for localization files.
 *
 * ### Supported syntax
 * - `[section]` and `[section.subsection]` headers
 * - `key = "value"` string assignments
 * - `# comment` lines (full-line only — inline comments after a value are NOT supported
 *   and will be treated as part of the value string)
 * - `@key` reference syntax: a value starting with `@` is replaced by the value of the
 *   referenced key, resolved up to [MAX_REFERENCE_DEPTH] levels deep to prevent infinite
 *   loops on circular references
 *
 * ### Special sections
 * - `[meta]` entries are parsed into [LocalizedLanguageMeta] rather than the entries map
 *
 * @param logger Optional logger. When provided, a [Logger.warn] is emitted whenever the
 *   `@reference` depth limit is hit (indicating a likely circular reference in a TOML file).
 */
class LanguageTomlParser(private val logger: Logger? = null) {

    private companion object {
        /** Maximum number of `@reference` hops before giving up, preventing infinite loops. */
        const val MAX_REFERENCE_DEPTH = 10
    }

    fun parse(content: String): ParsedLanguageFile {
        val entries     = mutableMapOf<String, String>()
        val meta        = mutableMapOf<String, String>()
        val currentPath = mutableListOf<String>()

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty()                              -> continue
                trimmed.startsWith("#")                        -> continue
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    val section = trimmed.removeSurrounding("[", "]").trim()
                    currentPath.clear()
                    currentPath.addAll(section.split("."))
                }
                "=" in trimmed -> {
                    val parts = trimmed.split("=", limit = 2)
                    val key   = parts[0].trim()
                    val value = processEscapes(parts[1].trim().removeSurrounding("\""))

                    if (currentPath.firstOrNull() == "meta") {
                        meta[key] = value
                    } else {
                        val fullKey = if (currentPath.isNotEmpty())
                            (currentPath + key).joinToString(".")
                        else key
                        entries[fullKey] = value
                    }
                }
            }
        }

        val metaData = if (meta.isNotEmpty()) {
            LocalizedLanguageMeta(
                name        = meta["name"]        ?: "Unknown",
                nativeName  = meta["native_name"] ?: meta["name"] ?: "Unknown",
                locale      = meta["locale"]      ?: "en-US",
                translators = parseTranslators(meta),
                isRtl       = meta["rtl"]?.toBooleanStrictOrNull() ?: false
            )
        } else null

        return ParsedLanguageFile(resolveReferences(entries), metaData)
    }

    /**
     * Everyone credited for a translation, newest format first.
     *
     * `translators` is a list because a translation outlives its first author. The older `author`
     * field is a single string and is still read, so a file written against the previous format
     * keeps crediting whoever it names instead of silently losing them.
     *
     * A legacy value is often `Name <email@example.com>`. The address is dropped rather than
     * shown: it was never meant for display, and putting someone's email on screen is not a
     * courtesy to them.
     */
    private fun parseTranslators(meta: Map<String, String>): List<String> {
        meta["translators"]?.let { return parseInlineArray(it) }

        return meta["author"]
            ?.substringBefore('<')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(it) }
            .orEmpty()
    }

    /**
     * Reads a single-line TOML array of strings, `["one", "two"]`.
     *
     * The parser keeps every value as the raw text after the `=`, which is enough for the strings
     * that make up the rest of a language file.      * is split here rather than growing the parser a general array type it would use once.
     */
    private fun parseInlineArray(raw: String): List<String> =
        raw.trim()
            .removeSurrounding("[", "]")
            .split(',')
            .map { it.trim().removeSurrounding("\"").trim() }
            .filter { it.isNotBlank() }

    private fun processEscapes(value: String): String =
        value
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")

    private fun resolveReferences(entries: Map<String, String>): Map<String, String> =
        entries.mapValues { (_, value) -> resolveReference(value, entries) }

    private fun resolveReference(value: String, entries: Map<String, String>): String {
        var result = value
        var depth  = 0
        while (result.startsWith("@") && depth < MAX_REFERENCE_DEPTH) {
            result = entries[result.removePrefix("@")] ?: break
            depth++
        }
        if (depth >= MAX_REFERENCE_DEPTH && result.startsWith("@")) {
            logger?.warn(
                "TOML reference depth limit ($MAX_REFERENCE_DEPTH) reached while resolving '$value'. " +
                "This usually indicates a circular reference in the localization file. " +
                "Returning the unresolved reference as-is."
            )
        }
        return result
    }
}
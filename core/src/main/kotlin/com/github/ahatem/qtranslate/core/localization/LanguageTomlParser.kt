package com.github.ahatem.qtranslate.core.localization

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
 */
class LanguageTomlParser {

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
                name       = meta["name"]        ?: "Unknown",
                nativeName = meta["native_name"] ?: meta["name"] ?: "Unknown",
                locale     = meta["locale"]      ?: "en-US",
                version    = meta["version"]     ?: "1.0.0",
                author     = meta["author"]      ?: "Community",
                lastUpdate = meta["last_updated"] ?: meta["last_update"] ?: LocalDate.now().toString(),
                isRtl      = meta["rtl"]?.toBooleanStrictOrNull() ?: false
            )
        } else null

        return ParsedLanguageFile(resolveReferences(entries), metaData)
    }

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
        return result
    }
}
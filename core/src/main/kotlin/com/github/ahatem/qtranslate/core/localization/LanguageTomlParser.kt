package com.github.ahatem.qtranslate.core.localization

import java.time.LocalDate

class LanguageTomlParser {

    fun parse(content: String): ParsedLanguageFile {
        val lines = content.lineSequence()
        val entries = mutableMapOf<String, String>()
        val meta = mutableMapOf<String, String>()
        val currentPath = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> continue
                trimmed.startsWith("#") -> continue
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    val section = trimmed.removeSurrounding("[", "]").trim()
                    currentPath.clear()
                    currentPath.addAll(section.split("."))
                }

                "=" in trimmed -> {
                    val parts = trimmed.split("=", limit = 2)
                    val key = parts[0].trim()
                    val value = parts[1].trim().removeSurrounding("\"")

                    if (currentPath.firstOrNull() == "meta") {
                        meta[key] = value
                    } else {
                        val fullKey = if (currentPath.isNotEmpty()) {
                            (currentPath + key).joinToString(".")
                        } else key
                        entries[fullKey] = value
                    }
                }
            }
        }

        val metaData = if (meta.isNotEmpty()) {
            LocalizedLanguageMeta(
                name = meta["name"] ?: "Unknown",
                nativeName = meta["native_name"] ?: meta["name"] ?: "Unknown",
                locale = meta["locale"] ?: "en-US",
                version = meta["version"] ?: "1.0.0",
                author = meta["author"] ?: "Community",
                lastUpdate = meta["last_updated"] ?: meta["last_update"] ?: LocalDate.now().toString(),
                isRtl = meta["rtl"]?.toBooleanStrictOrNull() ?: false
            )
        } else null

        return ParsedLanguageFile(resolveReferences(entries), metaData)
    }

    private fun resolveReferences(entries: Map<String, String>): Map<String, String> {
        val resolved = mutableMapOf<String, String>()
        for ((key, value) in entries) {
            resolved[key] = resolveReference(value, entries)
        }
        return resolved
    }

    private fun resolveReference(value: String, entries: Map<String, String>): String {
        var result = value
        var changed: Boolean
        do {
            changed = false
            if (result.startsWith("@")) {
                val refKey = result.removePrefix("@")
                val refValue = entries[refKey]
                if (refValue != null && refValue != result) {
                    result = refValue
                    changed = true
                }
            }
        } while (changed)
        return result
    }
}


package com.github.ahatem.qtranslate.core.localization

/**
 * Writes a translation file that looks like the English one it came from.
 *
 * ### Why the English file is the template
 * A language file is not really a map of strings, it is a document: sections in a deliberate
 * order, comments explaining what a placeholder means, blank lines separating one part of the
 * interface from another. Emitting a translation by walking a map would produce something
 * technically valid that no contributor would want to open, and every generated file would drift
 * a little further from every hand-written one.
 *
 * So the English file is walked line by line and reproduced exactly, with the values swapped. What
 * comes out sorts identically, comments in the same places, so a diff between a translation and
 * English shows only the translating.
 *
 * ### Untranslated keys are left out
 * A key with the English text in it is not a translation, it just looks like one: it defeats the
 * fallback it was supposed to trigger, and hides from the next contributor that anything is
 * missing. An untranslated key is therefore absent from the output entirely, which is what the
 * format has always meant by untranslated.
 */
class LanguageFileWriter(private val englishTemplate: String) {

    /**
     * Renders [translations] against the template.
     *
     * @param translations keyed as the parser produces them, `section.key`, holding only strings
     *   that have actually been translated.
     */
    fun write(meta: LanguageFileMeta, translations: Map<String, String>): String {
        val newline = if ("\r\n" in englishTemplate) "\r\n" else "\n"
        val out = mutableListOf<String>()
        var section = ""

        for (line in englishTemplate.split(newline)) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    section = trimmed.removeSurrounding("[", "]").trim()
                    out += line
                }

                trimmed.isEmpty() || trimmed.startsWith("#") -> out += line

                "=" in trimmed -> {
                    val key = trimmed.substringBefore('=').trim()
                    if (section == META_SECTION) {
                        metaLine(key, meta)?.let { out += it }
                    } else {
                        val fullKey = if (section.isEmpty()) key else "$section.$key"
                        translations[fullKey]?.let { out += "$key = ${quote(it)}" }
                    }
                }

                else -> out += line
            }
        }

        return collapseRuns(out, newline)
    }

    private fun metaLine(key: String, meta: LanguageFileMeta): String? = when (key) {
        "name" -> "name = ${quote(meta.name)}"
        "native_name" -> "native_name = ${quote(meta.nativeName)}"
        "locale" -> "locale = ${quote(meta.locale)}"
        "translators" -> "translators = [${meta.translators.joinToString(", ") { quote(it) }}]"
        "rtl" -> "rtl = ${meta.isRtl}"
        // A field the English file still carries but the format no longer uses. Dropping it here
        // rather than copying it keeps retired fields from coming back through every save.
        else -> null
    }

    /**
     * Reverses what the parser does to a value on the way in.
     *
     * The backslash goes first. Escaping it after the others would escape the backslashes they
     * had just introduced, turning a newline into a literal `\\n` on the next save, and doing it
     * again on the save after that.
     */
    private fun quote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    /**
     * Stops omitted keys leaving holes.
     *
     * A section where nothing is translated yet would otherwise emit its header and comments
     * followed by the blank lines that used to separate its keys, which reads as damage rather
     * than as absence. Runs of blank lines collapse to one, and any trailing blank goes.
     */
    private fun collapseRuns(lines: List<String>, newline: String): String {
        val result = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank() && result.lastOrNull()?.isBlank() == true) continue
            result += line
        }
        while (result.lastOrNull()?.isBlank() == true) result.removeAt(result.lastIndex)
        return result.joinToString(newline) + newline
    }

    private companion object {
        const val META_SECTION = "meta"
    }
}

/** The `[meta]` block of a translation file, as the editor works with it. */
data class LanguageFileMeta(
    val name: String,
    val nativeName: String,
    val locale: String,
    val translators: List<String>,
    val isRtl: Boolean
)

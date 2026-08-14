package com.github.ahatem.qtranslate.plugins.csv

import java.io.File

/** One row of the file, reduced to the parts a lookup needs. */
data class CsvRow(val term: String, val definition: String, val note: String?)

/**
 * A CSV file read into memory and keyed by term.
 *
 * Held in memory because a lookup happens while the user is reading — a term list is small
 * (glossaries run to thousands of rows, not millions) and re-reading the file per keystroke would
 * be slower and no more correct. The file is re-read when the settings change, not watched: a file
 * being edited underneath the app is rare, and a watcher is a thread and a failure mode for it.
 */
class CsvIndex private constructor(
    private val rows: Map<String, List<CsvRow>>,
    private val caseSensitive: Boolean
) {
    val size: Int get() = rows.size

    /** Every row whose term matches, in file order. Empty when nothing matches. */
    fun lookup(term: String): List<CsvRow> = rows[normalise(term)].orEmpty()

    private fun normalise(term: String) =
        term.trim().let { if (caseSensitive) it else it.lowercase() }

    companion object {

        /**
         * Reads [file] according to [settings].
         *
         * Rows that do not have the columns being asked for are skipped rather than failing the
         * whole file: a stray blank line or a trailing comment at the end of an otherwise good
         * export should not cost the user every other term in it.
         */
        fun read(file: File, settings: CsvSettings): CsvIndex {
            val delimiter = resolveDelimiter(settings.delimiter)
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return CsvIndex(emptyMap(), settings.caseSensitive)

            val header = splitLine(lines.first(), delimiter)
            val termIndex = resolveColumn(settings.termColumn, header)
            val definitionIndex = resolveColumn(settings.definitionColumn, header)
            val noteIndex = settings.notesColumn.takeIf { it.isNotBlank() }
                ?.let { resolveColumn(it, header) }

            // A column that cannot be located yields nothing, rather than reading some other
            // column and presenting the result as data. The usual cause is the wrong delimiter,
            // which makes every header name unfindable at once — and reading column one twice
            // would produce rows whose term and definition are the same string, which looks like
            // a working file until someone reads it.
            if (termIndex == null || definitionIndex == null) {
                return CsvIndex(emptyMap(), settings.caseSensitive)
            }

            // A column named rather than numbered means the first line describes the columns and
            // is not itself data.
            val hasHeaderRow = settings.termColumn.toIntOrNull() == null ||
                settings.definitionColumn.toIntOrNull() == null

            val body = if (hasHeaderRow) lines.drop(1) else lines

            val rows = body.mapNotNull { line ->
                val cells = splitLine(line, delimiter)
                val term = cells.getOrNull(termIndex)?.trim().orEmpty()
                val definition = cells.getOrNull(definitionIndex)?.trim().orEmpty()
                if (term.isEmpty() || definition.isEmpty()) return@mapNotNull null
                CsvRow(
                    term = term,
                    definition = definition,
                    note = noteIndex?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
                )
            }

            val keyed = rows.groupBy { row ->
                row.term.trim().let { if (settings.caseSensitive) it else it.lowercase() }
            }
            return CsvIndex(keyed, settings.caseSensitive)
        }

        /** `\t` in a text field is the two characters, not a tab. */
        private fun resolveDelimiter(configured: String): Char = when {
            configured == "\\t" -> '\t'
            configured.isEmpty() -> ','
            else -> configured.first()
        }

        /**
         * A column setting is either a header name or a 1-based number.
         *
         * Null when it is neither — a name absent from the header, or a number outside the file's
         * columns. Guessing a column instead would produce plausible-looking wrong answers.
         */
        private fun resolveColumn(configured: String, header: List<String>): Int? {
            val wanted = configured.trim()
            if (wanted.isEmpty()) return null

            val byName = header.indexOfFirst { it.trim().equals(wanted, ignoreCase = true) }
            if (byName >= 0) return byName

            // 1-based, because the first column of a spreadsheet is column 1 to everyone who is
            // not a programmer.
            val byNumber = wanted.toIntOrNull()?.minus(1) ?: return null
            return byNumber.takeIf { it >= 0 && it < header.size }
        }

        /**
         * Splits one line, honouring double quotes so a definition containing the delimiter
         * survives — which for a comma-separated glossary of prose definitions is most of them.
         */
        private fun splitLine(line: String, delimiter: Char): List<String> {
            val cells = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var index = 0

            while (index < line.length) {
                val char = line[index]
                when {
                    char == '"' && inQuotes && line.getOrNull(index + 1) == '"' -> {
                        // "" inside a quoted cell is one literal quote.
                        current.append('"')
                        index++
                    }
                    char == '"' -> inQuotes = !inQuotes
                    char == delimiter && !inQuotes -> {
                        cells += current.toString()
                        current.clear()
                    }
                    else -> current.append(char)
                }
                index++
            }
            cells += current.toString()
            return cells
        }
    }
}

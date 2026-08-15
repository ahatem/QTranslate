package com.github.ahatem.qtranslate.plugins.csv

import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingGroup
import com.github.ahatem.qtranslate.api.settings.SettingGroups
import com.github.ahatem.qtranslate.api.settings.SettingType

/**
 * Which file to read and how to read it.
 *
 * The columns are configurable rather than fixed. A glossary, an abbreviation list, a table of
 * error codes and a set of study mnemonics are the same shape — a term and something to say about
 * it — and asking which columns those are costs two settings and covers all of them.
 */
@SettingGroups(
    SettingGroup(key = "file", title = "File", order = 10),
    SettingGroup(key = "columns", title = "Columns", order = 20),
    SettingGroup(key = "matching", title = "Matching", order = 30, collapsible = true, defaultCollapsed = true)
)
data class CsvSettings(
    @field:Setting(
        label = "CSV file",
        description = "The file to look terms up in. The first row is treated as a header when " +
            "the column settings below name columns rather than numbers.",
        type = SettingType.FILE_PATH,
        isRequired = true,
        group = "file",
        order = 10
    )
    var filePath: String = "",

    @field:Setting(
        label = "Delimiter",
        description = "What separates the columns. Use \\t for a tab-separated file.",
        type = SettingType.TEXT,
        defaultValue = ",",
        group = "file",
        order = 20
    )
    var delimiter: String = ",",

    @field:Setting(
        label = "Term column",
        description = "The column holding the word being looked up — a header name, or a " +
            "1-based number for a file with no header row.",
        type = SettingType.TEXT,
        defaultValue = "1",
        isRequired = true,
        group = "columns",
        order = 10
    )
    var termColumn: String = "1",

    @field:Setting(
        label = "Definition column",
        description = "The column holding the text to show for that term.",
        type = SettingType.TEXT,
        defaultValue = "2",
        isRequired = true,
        group = "columns",
        order = 20
    )
    var definitionColumn: String = "2",

    @field:Setting(
        label = "Notes column",
        description = "Optional. Shown under the definition as an example — a source, a mnemonic, " +
            "a usage note. Leave blank if the file has no such column.",
        type = SettingType.TEXT,
        defaultValue = "",
        group = "columns",
        order = 30
    )
    var notesColumn: String = "",

    @field:Setting(
        label = "Match case",
        description = "When off, 'DNS' and 'dns' find the same row.",
        type = SettingType.BOOLEAN,
        defaultValue = "false",
        group = "matching",
        order = 10
    )
    var caseSensitive: Boolean = false,

    @field:Setting(
        label = "Label",
        description = "What to show as the part of speech beside each result. Useful when the " +
            "file is not a dictionary — 'abbreviation', 'error code', 'mnemonic'.",
        type = SettingType.TEXT,
        defaultValue = "term",
        group = "matching",
        order = 20
    )
    var entryLabel: String = "term"
) : PluginSettings.Configurable()

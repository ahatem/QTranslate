package com.github.ahatem.qtranslate.plugins.csv

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Looks terms up in a CSV file the user supplies.
 *
 * Built to answer a request for a mnemonics list, but the columns are configurable, so the same
 * plugin serves a glossary of internal jargon, an abbreviation list or a table of error codes.
 */
class CsvPlugin : Plugin<CsvSettings> {

    private lateinit var context: PluginContext
    private var settings = CsvSettings()
    private var index: CsvIndex? = null
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        settings = CsvSettings(
            filePath = context.settings.getString(KEY_FILE_PATH).orEmpty(),
            delimiter = context.settings.getString(KEY_DELIMITER) ?: ",",
            termColumn = context.settings.getString(KEY_TERM_COLUMN) ?: "1",
            definitionColumn = context.settings.getString(KEY_DEFINITION_COLUMN) ?: "2",
            notesColumn = context.settings.getString(KEY_NOTES_COLUMN).orEmpty(),
            caseSensitive = context.settings.getBoolean(KEY_CASE_SENSITIVE, false),
            entryLabel = context.settings.getString(KEY_ENTRY_LABEL) ?: "term"
        )
        loadIndex()
        context.logger.info("CSV plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        services = listOf(CsvDictionaryService(settings = { settings }, index = { index }))
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        services = emptyList()
    }

    override suspend fun onSettingsChanged(settings: CsvSettings): Result<Unit, ServiceError> {
        this.settings = settings
        context.settings.put(KEY_FILE_PATH, settings.filePath)
        context.settings.put(KEY_DELIMITER, settings.delimiter)
        context.settings.put(KEY_TERM_COLUMN, settings.termColumn)
        context.settings.put(KEY_DEFINITION_COLUMN, settings.definitionColumn)
        context.settings.put(KEY_NOTES_COLUMN, settings.notesColumn)
        context.settings.put(KEY_CASE_SENSITIVE, settings.caseSensitive)
        context.settings.put(KEY_ENTRY_LABEL, settings.entryLabel)

        // Re-read rather than reload lazily: the user has just pressed Save and is in a position
        // to be told the file is unreadable, which they will not be later.
        loadIndex()
        return Ok(Unit)
    }

    override suspend fun shutdown() {
        index = null
    }

    override fun getServices(): List<Service> = services

    override fun getSettings(): CsvSettings = settings

    /**
     * Reads the file into memory, leaving the previous index in place on failure.
     *
     * Failure is reported through the log and through the service's own check rather than by
     * refusing to initialize: a plugin that will not load because a path is wrong cannot show the
     * user the settings screen where they would fix it.
     */
    private suspend fun loadIndex() {
        val path = settings.filePath
        if (path.isBlank()) {
            index = null
            return
        }

        index = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (!file.isFile) {
                    context.logger.warn("CSV file not found: $path")
                    return@runCatching null
                }
                CsvIndex.read(file, settings).also {
                    context.logger.info("Indexed ${it.size} term(s) from $path")
                }
            }.getOrElse { error ->
                context.logger.error("Could not read CSV file '$path'", error)
                null
            }
        }
    }

    private companion object {
        const val KEY_FILE_PATH = "filePath"
        const val KEY_DELIMITER = "delimiter"
        const val KEY_TERM_COLUMN = "termColumn"
        const val KEY_DEFINITION_COLUMN = "definitionColumn"
        const val KEY_NOTES_COLUMN = "notesColumn"
        const val KEY_CASE_SENSITIVE = "caseSensitive"
        const val KEY_ENTRY_LABEL = "entryLabel"
    }
}

package com.github.ahatem.qtranslate.plugins.csv

import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceCapability
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CsvPluginTest {

    private val directory = Files.createTempDirectory("csv-plugin-test").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun glossary(): File = File(directory, "glossary.csv").apply {
        writeText(
            """
            term,meaning,source
            DNS,Domain Name System,RFC 1035
            """.trimIndent()
        )
    }

    private suspend fun enabledPlugin(settings: CsvSettings): Pair<CsvPlugin, FakePluginContext> {
        val context = FakePluginContext()
        val plugin = CsvPlugin()
        plugin.initialize(context)
        plugin.onSettingsChanged(settings)
        plugin.onEnable()
        return plugin to context
    }

    @Test
    fun `declares one dictionary service`() = runBlocking<Unit> {
        val (plugin, _) = enabledPlugin(CsvSettings(filePath = glossary().absolutePath))

        val service = plugin.getServices().single()
        assertEquals("csv-dictionary", service.key)
        assertEquals(setOf(ServiceCapability.DICTIONARY), service.capabilities)
    }

    @Test
    fun `looks a term up through the service`() = runBlocking<Unit> {
        val (plugin, _) = enabledPlugin(
            CsvSettings(
                filePath = glossary().absolutePath,
                termColumn = "term",
                definitionColumn = "meaning",
                notesColumn = "source"
            )
        )

        val service = plugin.getServices().single() as CsvDictionaryService
        val entry = service.lookup(DictionaryRequest("DNS", LanguageCode.ENGLISH)).get()!!.entries.single()

        assertEquals("DNS", entry.word)
        assertEquals("Domain Name System", entry.definitions.single().text)
        assertEquals("RFC 1035", entry.definitions.single().example)
    }

    @Test
    fun `a miss is an empty result, not an error`() = runBlocking<Unit> {
        // The dictionary panel already says "nothing found"; an error would put a red status bar
        // in front of the user for the ordinary case of a word not being in their own file.
        val (plugin, _) = enabledPlugin(
            CsvSettings(filePath = glossary().absolutePath, termColumn = "term", definitionColumn = "meaning")
        )

        val service = plugin.getServices().single() as CsvDictionaryService
        val response = service.lookup(DictionaryRequest("nowhere", LanguageCode.ENGLISH)).get()

        assertTrue(response!!.entries.isEmpty())
    }

    @Test
    fun `settings survive a restart`() = runBlocking<Unit> {
        val context = FakePluginContext()
        val path = glossary().absolutePath

        CsvPlugin().apply {
            initialize(context)
            onSettingsChanged(CsvSettings(filePath = path, termColumn = "term", definitionColumn = "meaning"))
        }

        // A second plugin over the same storage is what the next launch looks like.
        val restarted = CsvPlugin()
        restarted.initialize(context)

        assertEquals(path, restarted.getSettings().filePath)
        assertEquals("term", restarted.getSettings().termColumn)
    }

    @Test
    fun `validate reports an unset file as needing configuration`() = runBlocking<Unit> {
        val (plugin, _) = enabledPlugin(CsvSettings(filePath = ""))

        val error = plugin.getServices().single().validate().getError()

        assertIs<ServiceError.ConfigurationError>(error)
    }

    @Test
    fun `validate reports a file that exists but yields nothing usable`() = runBlocking<Unit> {
        // The likeliest real mistake: right file, wrong delimiter.
        val file = File(directory, "semicolons.csv").apply { writeText("term;meaning\nDNS;Domain Name System") }
        val (plugin, _) = enabledPlugin(
            CsvSettings(filePath = file.absolutePath, delimiter = ",", termColumn = "term", definitionColumn = "meaning")
        )

        val error = plugin.getServices().single().validate().getError()

        assertIs<ServiceError.ConfigurationError>(error)
    }

    @Test
    fun `validate passes for a readable file`() = runBlocking<Unit> {
        val (plugin, _) = enabledPlugin(
            CsvSettings(filePath = glossary().absolutePath, termColumn = "term", definitionColumn = "meaning")
        )

        assertTrue(plugin.getServices().single().validate().get() != null)
    }

    @Test
    fun `a missing file leaves the plugin usable so the user can fix the path`() = runBlocking<Unit> {
        // Refusing to initialize would hide the settings screen where the path is corrected.
        val (plugin, _) = enabledPlugin(CsvSettings(filePath = File(directory, "gone.csv").absolutePath))

        val service = plugin.getServices().single() as CsvDictionaryService
        val error = service.lookup(DictionaryRequest("DNS", LanguageCode.ENGLISH)).getError()

        assertIs<ServiceError.ConfigurationError>(error)
    }
}

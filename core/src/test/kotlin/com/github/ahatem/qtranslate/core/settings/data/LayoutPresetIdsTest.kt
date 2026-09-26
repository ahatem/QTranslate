package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Persisted layout ids: the three current layouts, and how a retired or unknown id is read. */
class LayoutPresetIdsTest {

    private val translators = listOf("google", "bing")

    private fun config(layoutPresetId: String) = Configuration.DEFAULT.copy(
        servicePresets = listOf(
            ServicePreset(
                id = "preset",
                name = "preset",
                selectedServices = mapOf(ServiceRole.TRANSLATOR to "google"),
                comparisonTranslatorIds = listOf("bing")
            )
        ),
        activeServicePresetId = "preset",
        layoutPresetId = layoutPresetId
    )

    @Test
    fun `current layouts resolve to themselves`() {
        assertEquals(LayoutPresetIds.CLASSIC, LayoutPresetIds.resolve(LayoutPresetIds.CLASSIC))
        assertEquals(LayoutPresetIds.SIDE_BY_SIDE, LayoutPresetIds.resolve(LayoutPresetIds.SIDE_BY_SIDE))
        assertEquals(LayoutPresetIds.COMPARISON, LayoutPresetIds.resolve(LayoutPresetIds.COMPARISON))
    }

    @Test
    fun `the retired compact layout resolves to classic`() {
        assertEquals("compact", LayoutPresetIds.LEGACY_COMPACT)
        assertEquals(LayoutPresetIds.CLASSIC, LayoutPresetIds.resolve(LayoutPresetIds.LEGACY_COMPACT))
    }

    @Test
    fun `an unknown layout id falls back to classic`() {
        assertEquals(LayoutPresetIds.CLASSIC, LayoutPresetIds.resolve("from_a_newer_version"))
        assertEquals(LayoutPresetIds.CLASSIC, LayoutPresetIds.resolve(""))
    }

    @Test
    fun `the runtime arrangement of a saved compact layout is classic`() {
        assertEquals(LayoutPresetIds.CLASSIC, config("compact").effectiveLayoutPresetId(translators))
    }

    @Test
    fun `an eligible comparison stays comparison and side by side is untouched`() {
        assertEquals(LayoutPresetIds.COMPARISON, config("comparison").effectiveLayoutPresetId(translators))
        assertEquals(LayoutPresetIds.SIDE_BY_SIDE, config("side_by_side").effectiveLayoutPresetId(translators))
    }

    @Test
    fun `an ineligible comparison still falls back without losing the saved preference`() {
        val saved = config("comparison")
        assertEquals(LayoutPresetIds.CLASSIC, saved.effectiveLayoutPresetId(listOf("google")))
        assertEquals(LayoutPresetIds.COMPARISON, saved.layoutPresetId)
    }

    @Test
    fun `only a retired id is rewritten`() {
        assertEquals(LayoutPresetIds.CLASSIC, config("compact").withoutRetiredLayout().layoutPresetId)

        val side = config("side_by_side")
        assertSame(side, side.withoutRetiredLayout())
        val unknown = config("from_a_newer_version")
        assertSame(unknown, unknown.withoutRetiredLayout())
    }

    @Test
    fun `a stored compact layout loads as classic`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-layout").toFile()
        try {
            val logger = object : Logger {
                override fun debug(message: String) = Unit
                override fun info(message: String) = Unit
                override fun warn(message: String) = Unit
                override fun error(message: String, error: Throwable?) = Unit
            }
            val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
            repository.updateConfiguration(Configuration.DEFAULT.copy(layoutPresetId = "compact"))

            assertEquals(LayoutPresetIds.CLASSIC, repository.configuration.first().layoutPresetId)
        } finally {
            directory.deleteRecursively()
        }
    }
}

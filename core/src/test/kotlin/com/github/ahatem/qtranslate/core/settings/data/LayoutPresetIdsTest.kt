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
        layoutPresetId = layoutPresetId,
        // The arrangement the window actually reads; the id beside it is the frozen v10 field the
        // same value is migrated from, kept in step here the way a migrated configuration has it.
        windowLayout = WindowLayout(panes = PaneLayout.fromPresetId(layoutPresetId))
    )

    @Test
    fun `layout ids resolve to a current layout`() {
        val expected = mapOf(
            LayoutPresetIds.CLASSIC to LayoutPresetIds.CLASSIC,
            LayoutPresetIds.SIDE_BY_SIDE to LayoutPresetIds.SIDE_BY_SIDE,
            LayoutPresetIds.COMPARISON to LayoutPresetIds.COMPARISON,
            // The retired compact layout and ids from a newer version read as classic.
            LayoutPresetIds.LEGACY_COMPACT to LayoutPresetIds.CLASSIC,
            "from_a_newer_version" to LayoutPresetIds.CLASSIC,
            "" to LayoutPresetIds.CLASSIC,
        )
        expected.forEach { (stored, resolved) ->
            assertEquals(resolved, LayoutPresetIds.resolve(stored), "stored id '$stored'")
        }
    }

    @Test
    fun `the runtime arrangement follows the saved layout when it is eligible`() {
        val expected = mapOf(
            "compact" to LayoutPresetIds.CLASSIC,
            "comparison" to LayoutPresetIds.COMPARISON,
            "side_by_side" to LayoutPresetIds.SIDE_BY_SIDE,
        )
        expected.forEach { (saved, runtime) ->
            assertEquals(runtime, config(saved).effectiveLayoutPresetId(translators), "saved '$saved'")
        }
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

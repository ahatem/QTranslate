package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Translator-set promotion and Comparison eligibility (#292 merge blockers).
 *
 * Promotion keeps ONE set + ONE primary: the old primary takes the promoted
 * member's comparison slot and repeated promotions never shrink the set.
 * Eligibility is static configuration/service availability — no network health.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComparisonTranslatorSetTest {
    private val logger = object : com.github.ahatem.qtranslate.api.core.Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val directories = mutableListOf<java.io.File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cleanUp() {
        scopes.forEach { it.coroutineContext.cancel() }
        directories.forEach { it.deleteRecursively() }
    }

    private fun preset(
        primary: String?,
        comparisons: List<String> = emptyList()
    ) = ServicePreset(
        id = "preset",
        name = "preset",
        selectedServices = mapOf(ServiceRole.TRANSLATOR to primary),
        comparisonTranslatorIds = comparisons
    )

    private fun config(
        primary: String?,
        comparisons: List<String> = emptyList(),
        layoutPresetId: String = LayoutPresetIds.CLASSIC,
        disabledServices: Set<String> = emptySet(),
        translatorRoleEnabled: Boolean = true
    ): Configuration {
        val base = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset(primary, comparisons)),
            activeServicePresetId = "preset",
            layoutPresetId = layoutPresetId,
            disabledServices = disabledServices
        )
        return base.withServiceRoleEnabled(ServiceRole.TRANSLATOR, translatorRoleEnabled)
    }

    // ---- 1. promoting a comparison translator preserves all membership ----

    @Test
    fun `promoting bing keeps google bing and deepl`() {
        val promoted = preset("google", listOf("bing", "deepl"))
            .withPromotedTranslator("bing")

        assertEquals("bing", promoted.selectedServices[ServiceRole.TRANSLATOR])
        assertEquals(
            setOf("google", "bing", "deepl"),
            (promoted.comparisonTranslatorIds + promoted.selectedServices[ServiceRole.TRANSLATOR]).toSet()
        )
    }

    // ---- 2. old primary replaces the promoted member in comparison ordering ----

    @Test
    fun `old primary takes the promoted slot preserving order`() {
        val promoted = preset("google", listOf("bing", "deepl"))
            .withPromotedTranslator("bing")

        assertEquals(listOf("google", "deepl"), promoted.comparisonTranslatorIds)
    }

    // ---- 3. repeated primary changes never shrink the translator set ----

    @Test
    fun `repeated promotions never shrink the set`() {
        var current = preset("google", listOf("bing", "deepl"))
        listOf("bing", "deepl", "google", "bing").forEach { id ->
            current = current.withPromotedTranslator(id)
            val members = (current.comparisonTranslatorIds + current.selectedServices[ServiceRole.TRANSLATOR]).toSet()
            assertEquals(setOf("google", "bing", "deepl"), members, "promoting $id shrank the set")
        }
    }

    // ---- 4. no duplicates produced ----

    @Test
    fun `promotion never duplicates ids`() {
        val promoted = preset("google", listOf("bing", "deepl", "bing"))
            .withPromotedTranslator("deepl")

        val all = promoted.comparisonTranslatorIds + promoted.selectedServices[ServiceRole.TRANSLATOR]
        assertEquals(all.size, all.toSet().size, "duplicates produced: $all")
    }

    @Test
    fun `promoting the current primary is a stable no-op`() {
        val promoted = preset("google", listOf("bing", "google", "deepl"))
            .withPromotedTranslator("google")

        assertEquals("google", promoted.selectedServices[ServiceRole.TRANSLATOR])
        assertEquals(listOf("bing", "deepl"), promoted.comparisonTranslatorIds)
    }

    @Test
    fun `promoting an unlisted translator appends the old primary`() {
        val promoted = preset("google", listOf("bing"))
            .withPromotedTranslator("deepl")

        assertEquals("deepl", promoted.selectedServices[ServiceRole.TRANSLATOR])
        assertEquals(listOf("bing", "google"), promoted.comparisonTranslatorIds)
    }

    // ---- 5. unavailable ids remain configured but are not executed ----

    @Test
    fun `unavailable ids stay stored while excluded from effective execution set`() {
        val cfg = config("google", listOf("bing", "ghost"))
        val effective = cfg.effectiveTranslatorIds(setOf("google", "bing"))

        assertEquals(listOf("google", "bing"), effective)
        // Stored configuration is untouched: Settings still shows "ghost".
        assertEquals(
            listOf("bing", "ghost"),
            cfg.getActivePreset()!!.comparisonTranslatorIds
        )
    }

    @Test
    fun `promotion intent updates the working draft preserving the set`() = runTest {
        val store = store(config("google", listOf("bing", "deepl")))

        store.dispatch(SettingsIntent.PromoteTranslatorToPrimary("bing"))

        val updated = store.state.value.workingConfiguration.getActivePreset()!!
        assertEquals("bing", updated.selectedServices[ServiceRole.TRANSLATOR])
        assertEquals(listOf("google", "deepl"), updated.comparisonTranslatorIds)
    }

    // ---- 7. one effective translator means comparison unavailable ----

    @Test
    fun `one effective translator leaves comparison unavailable`() {
        assertFalse(config("google").isComparisonEligible(setOf("google")))
    }

    // ---- 8. two effective translators mean comparison available ----

    @Test
    fun `two effective translators make comparison available`() {
        assertTrue(config("google", listOf("bing")).isComparisonEligible(setOf("google", "bing")))
    }

    // ---- 9. disabled secondary does not count ----

    @Test
    fun `disabled secondary does not count`() {
        val cfg = config("google", listOf("bing"), disabledServices = setOf("bing"))
        assertEquals(listOf("google"), cfg.effectiveTranslatorIds(setOf("google", "bing")))
        assertFalse(cfg.isComparisonEligible(setOf("google", "bing")))
    }

    // ---- 10. unavailable secondary does not count ----

    @Test
    fun `unavailable secondary does not count`() {
        val cfg = config("google", listOf("ghost"))
        assertEquals(listOf("google"), cfg.effectiveTranslatorIds(setOf("google")))
        assertFalse(cfg.isComparisonEligible(setOf("google")))
    }

    // ---- 11. duplicate id does not count twice ----

    @Test
    fun `duplicate id does not count twice`() {
        val cfg = config("google", listOf("bing", "bing"))
        assertEquals(listOf("google", "bing"), cfg.effectiveTranslatorIds(setOf("google", "bing")))
        assertTrue(cfg.isComparisonEligible(setOf("google", "bing")))
        assertFalse(config("google", listOf("google")).isComparisonEligible(setOf("google")))
    }

    // ---- 12. resolved fallback primary is counted correctly ----

    @Test
    fun `unresolvable primary falls back without counting`() {
        // Configured primary is gone but two comparisons resolve: still eligible,
        // and the comparisons alone form the effective set.
        val cfg = config("ghost", listOf("bing", "deepl"))
        assertEquals(listOf("bing", "deepl"), cfg.effectiveTranslatorIds(setOf("bing", "deepl")))
        assertTrue(cfg.isComparisonEligible(setOf("bing", "deepl")))
    }

    @Test
    fun `disabled translator role disables comparison entirely`() {
        val cfg = config("google", listOf("bing"), translatorRoleEnabled = false)
        assertTrue(cfg.effectiveTranslatorIds(setOf("google", "bing")).isEmpty())
        assertFalse(cfg.isComparisonEligible(setOf("google", "bing")))
    }

    // ---- 13. requested comparison without eligibility uses classic ----

    @Test
    fun `requested comparison without eligibility falls back to classic`() {
        val cfg = config("google", emptyList(), layoutPresetId = LayoutPresetIds.COMPARISON)
        assertEquals(LayoutPresetIds.CLASSIC, cfg.effectiveLayoutPresetId(setOf("google")))
    }

    // ---- 14. saved configuration still says comparison ----

    @Test
    fun `fallback never rewrites the saved layout preference`() {
        val cfg = config("google", emptyList(), layoutPresetId = LayoutPresetIds.COMPARISON)
        cfg.effectiveLayoutPresetId(setOf("google"))
        assertEquals(LayoutPresetIds.COMPARISON, cfg.layoutPresetId)
    }

    // ---- 15. becoming eligible again allows comparison ----

    @Test
    fun `restored eligibility makes comparison effective again`() {
        val cfg = config("google", listOf("bing"), layoutPresetId = LayoutPresetIds.COMPARISON)
        assertEquals(LayoutPresetIds.CLASSIC, cfg.effectiveLayoutPresetId(setOf("google")))
        assertEquals(LayoutPresetIds.COMPARISON, cfg.effectiveLayoutPresetId(setOf("google", "bing")))
    }

    @Test
    fun `eligible comparison keeps its saved layout`() {
        val cfg = config("google", listOf("bing"), layoutPresetId = LayoutPresetIds.COMPARISON)
        assertEquals(LayoutPresetIds.COMPARISON, cfg.effectiveLayoutPresetId(setOf("google", "bing")))
    }

    private suspend fun store(config: Configuration): SettingsStore {
        val directory = Files.createTempDirectory("qtranslate-promotion").toFile()
        directories += directory
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return SettingsStore(repository, logger, scope, config)
    }
}

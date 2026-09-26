package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
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
        val effective = cfg.effectiveTranslatorIds(listOf("google", "bing"))

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
        assertFalse(config("google").isComparisonEligible(listOf("google")))
    }

    // ---- 8. two effective translators mean comparison available ----

    @Test
    fun `two effective translators make comparison available`() {
        assertTrue(config("google", listOf("bing")).isComparisonEligible(listOf("google", "bing")))
    }

    // ---- 9. disabled secondary does not count ----

    @Test
    fun `disabled secondary does not count`() {
        val cfg = config("google", listOf("bing"), disabledServices = setOf("bing"))
        assertEquals(listOf("google"), cfg.effectiveTranslatorIds(listOf("google", "bing")))
        assertFalse(cfg.isComparisonEligible(listOf("google", "bing")))
    }

    // ---- 10. unavailable secondary does not count ----

    @Test
    fun `unavailable secondary does not count`() {
        val cfg = config("google", listOf("ghost"))
        assertEquals(listOf("google"), cfg.effectiveTranslatorIds(listOf("google")))
        assertFalse(cfg.isComparisonEligible(listOf("google")))
    }

    // ---- 11. duplicate id does not count twice ----

    @Test
    fun `duplicate id does not count twice`() {
        val cfg = config("google", listOf("bing", "bing"))
        assertEquals(listOf("google", "bing"), cfg.effectiveTranslatorIds(listOf("google", "bing")))
        assertTrue(cfg.isComparisonEligible(listOf("google", "bing")))
        assertFalse(config("google", listOf("google")).isComparisonEligible(listOf("google")))
    }

    // ---- 12. the REAL resolved (fallback) primary is counted ----

    @Test
    fun `unavailable saved primary falls back to first installed translator and counts`() {
        // saved primary ghost, one comparison deepl, google is installed first:
        // ActiveServiceManager resolves google, so the set is [google, deepl].
        val cfg = config("ghost", listOf("deepl"))
        val installed = listOf("google", "deepl")

        assertEquals(listOf("google", "deepl"), cfg.effectiveTranslatorIds(installed))
        assertTrue(cfg.isComparisonEligible(installed))
    }

    @Test
    fun `fallback primary that is also a configured comparison is not counted twice`() {
        val cfg = config("ghost", listOf("google", "deepl"))

        assertEquals(listOf("google", "deepl"), cfg.effectiveTranslatorIds(listOf("google", "deepl")))
    }

    @Test
    fun `fallback never adopts arbitrary installed translators as comparisons`() {
        // google resolves as primary; the installed-but-unconfigured bing is NOT a member.
        val cfg = config("ghost", emptyList())

        assertEquals(listOf("google"), cfg.effectiveTranslatorIds(listOf("google", "bing")))
        assertFalse(cfg.isComparisonEligible(listOf("google", "bing")))
    }

    @Test
    fun `disabled translator is never the fallback primary`() {
        // google is disabled, so the resolved fallback is bing; only deepl remains as comparison.
        val cfg = config("ghost", listOf("deepl"), disabledServices = setOf("google"))

        assertEquals(listOf("bing", "deepl"), cfg.effectiveTranslatorIds(listOf("google", "bing", "deepl")))
    }

    @Test
    fun `effective order is resolved primary then persisted comparison order`() {
        val cfg = config("ghost", listOf("deepl", "ghost2", "bing", "deepl"))

        assertEquals(
            listOf("google", "deepl", "bing"),
            cfg.effectiveTranslatorIds(listOf("google", "bing", "deepl"))
        )
    }

    @Test
    fun `no usable translator means no primary and an empty set`() {
        // Comparison needs a canonical translation, which translation cannot produce without
        // a Primary. Nothing installed => nothing effective, even with comparisons configured.
        val cfg = config("ghost", listOf("deepl"))

        assertTrue(cfg.effectiveTranslatorIds(emptyList()).isEmpty())
        assertFalse(cfg.isComparisonEligible(emptyList()))
        // All installed translators disabled behaves the same.
        val allDisabled = config("google", listOf("deepl"), disabledServices = setOf("google", "deepl"))
        assertTrue(allDisabled.effectiveTranslatorIds(listOf("google", "deepl")).isEmpty())
    }

    @Test
    fun `effective primary always matches ActiveServiceManager resolution`() {
        val installed = listOf("google", "bing", "deepl")
        val services: Map<String, com.github.ahatem.qtranslate.api.plugin.Service> =
            installed.associateWith { FakeTranslator(it) }
        listOf(
            config("google", listOf("bing")),
            config("ghost", listOf("deepl")),
            config("ghost", listOf("google", "deepl")),
            config("bing", listOf("deepl"), disabledServices = setOf("bing")),
            config("ghost", emptyList(), disabledServices = setOf("google")),
            config("ghost", listOf("deepl"), translatorRoleEnabled = false),
            config(null, listOf("bing"))
        ).forEach { cfg ->
            val manager = ActiveServiceManager(MutableStateFlow(services), MutableStateFlow(cfg))
            val resolved = manager.getActive<com.github.ahatem.qtranslate.api.plugin.Service>(ServiceRole.TRANSLATOR)?.id
            assertEquals(
                resolved,
                cfg.effectiveTranslatorIds(installed).firstOrNull(),
                "effective primary diverged from ActiveServiceManager for $cfg"
            )
        }
    }

    @Test
    fun `disabled translator role disables comparison entirely`() {
        val cfg = config("google", listOf("bing"), translatorRoleEnabled = false)
        assertTrue(cfg.effectiveTranslatorIds(listOf("google", "bing")).isEmpty())
        assertFalse(cfg.isComparisonEligible(listOf("google", "bing")))
    }

    // ---- 13. requested comparison without eligibility uses classic ----

    @Test
    fun `requested comparison without eligibility falls back to classic`() {
        val cfg = config("google", emptyList(), layoutPresetId = LayoutPresetIds.COMPARISON)
        assertEquals(LayoutPresetIds.CLASSIC, cfg.effectiveLayoutPresetId(listOf("google")))
    }

    // ---- 14. saved configuration still says comparison ----

    @Test
    fun `fallback never rewrites the saved layout preference`() {
        val cfg = config("google", emptyList(), layoutPresetId = LayoutPresetIds.COMPARISON)
        cfg.effectiveLayoutPresetId(listOf("google"))
        assertEquals(LayoutPresetIds.COMPARISON, cfg.layoutPresetId)
    }

    // ---- 15. becoming eligible again allows comparison ----

    @Test
    fun `restored eligibility makes comparison effective again`() {
        val cfg = config("google", listOf("bing"), layoutPresetId = LayoutPresetIds.COMPARISON)
        assertEquals(LayoutPresetIds.CLASSIC, cfg.effectiveLayoutPresetId(listOf("google")))
        assertEquals(LayoutPresetIds.COMPARISON, cfg.effectiveLayoutPresetId(listOf("google", "bing")))
    }

    @Test
    fun `eligible comparison keeps its saved layout`() {
        val cfg = config("google", listOf("bing"), layoutPresetId = LayoutPresetIds.COMPARISON)
        assertEquals(LayoutPresetIds.COMPARISON, cfg.effectiveLayoutPresetId(listOf("google", "bing")))
    }

    private class FakeTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse(key))
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

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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Translator-set promotion and Comparison eligibility (#292 merge blockers).
 *
 * Promotion keeps ONE set + ONE primary: the old primary takes the promoted
 * member's comparison slot and repeated promotions never shrink the set.
 * Eligibility is static configuration/service availability, with no network health.
 */
class ComparisonTranslatorSetTest {

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
        disabledServices: Set<String> = emptySet(),
        translatorRoleEnabled: Boolean = true
    ): Configuration {
        val base = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset(primary, comparisons)),
            activeServicePresetId = "preset",
            disabledServices = disabledServices
        )
        return base.withServiceRoleEnabled(ServiceRole.TRANSLATOR, translatorRoleEnabled)
    }

    @Test
    fun `promotion swaps the old primary into the promoted slot`() {
        // (before, promoted) -> (new primary, new comparisons)
        val cases = listOf(
            Triple(preset("google", listOf("bing", "deepl")), "bing", "bing" to listOf("google", "deepl")),
            Triple(preset("google", listOf("bing", "google", "deepl")), "google", "google" to listOf("bing", "deepl")),
            Triple(preset("google", listOf("bing")), "deepl", "deepl" to listOf("bing", "google")),
        )
        cases.forEach { (before, promoted, expected) ->
            val after = before.withPromotedTranslator(promoted)
            assertEquals(expected.first, after.selectedServices[ServiceRole.TRANSLATOR], "primary after promoting $promoted")
            assertEquals(expected.second, after.comparisonTranslatorIds, "comparisons after promoting $promoted")
        }
    }

    @Test
    fun `repeated promotions never shrink or duplicate the set`() {
        var current = preset("google", listOf("bing", "deepl", "bing"))
        listOf("bing", "deepl", "google", "bing").forEach { id ->
            current = current.withPromotedTranslator(id)
            val members = current.comparisonTranslatorIds + current.selectedServices[ServiceRole.TRANSLATOR]
            assertEquals(setOf("google", "bing", "deepl"), members.toSet(), "promoting $id changed the set")
            assertEquals(members.size, members.toSet().size, "promoting $id produced duplicates: $members")
        }
    }

    @Test
    fun `unavailable ids stay stored while excluded from the effective execution set`() {
        val cfg = config("google", listOf("bing", "ghost"))

        assertEquals(listOf("google", "bing"), cfg.effectiveTranslatorIds(listOf("google", "bing")))
        // Stored configuration is untouched: Settings still shows "ghost".
        assertEquals(listOf("bing", "ghost"), cfg.getActivePreset()!!.comparisonTranslatorIds)
    }

    private class EffectiveCase(
        val name: String,
        val config: Configuration,
        val installed: List<String>,
        val effective: List<String>,
        val eligible: Boolean,
    )

    @Test
    fun `the effective translator set and comparison eligibility`() {
        val cases = listOf(
            EffectiveCase("one translator", config("google"), listOf("google"), listOf("google"), false),
            EffectiveCase("two translators", config("google", listOf("bing")), listOf("google", "bing"), listOf("google", "bing"), true),
            EffectiveCase(
                "disabled secondary",
                config("google", listOf("bing"), disabledServices = setOf("bing")),
                listOf("google", "bing"), listOf("google"), false,
            ),
            EffectiveCase("unavailable secondary", config("google", listOf("ghost")), listOf("google"), listOf("google"), false),
            EffectiveCase("duplicate id", config("google", listOf("bing", "bing")), listOf("google", "bing"), listOf("google", "bing"), true),
            EffectiveCase("primary repeated as comparison", config("google", listOf("google")), listOf("google"), listOf("google"), false),
            EffectiveCase(
                "translator role disabled",
                config("google", listOf("bing"), translatorRoleEnabled = false),
                listOf("google", "bing"), emptyList(), false,
            ),
            EffectiveCase("nothing installed", config("ghost", listOf("deepl")), emptyList(), emptyList(), false),
            EffectiveCase(
                "everything disabled",
                config("google", listOf("deepl"), disabledServices = setOf("google", "deepl")),
                listOf("google", "deepl"), emptyList(), false,
            ),
            EffectiveCase(
                "unavailable saved primary falls back to the first installed translator",
                config("ghost", listOf("deepl")),
                listOf("google", "deepl"), listOf("google", "deepl"), true,
            ),
            EffectiveCase(
                "fallback primary that is also a comparison counts once",
                config("ghost", listOf("google", "deepl")),
                listOf("google", "deepl"), listOf("google", "deepl"), true,
            ),
            EffectiveCase(
                "fallback never adopts unconfigured translators",
                config("ghost"),
                listOf("google", "bing"), listOf("google"), false,
            ),
            EffectiveCase(
                "a disabled translator is never the fallback primary",
                config("ghost", listOf("deepl"), disabledServices = setOf("google")),
                listOf("google", "bing", "deepl"), listOf("bing", "deepl"), true,
            ),
            EffectiveCase(
                "order is the resolved primary then the persisted comparison order",
                config("ghost", listOf("deepl", "ghost2", "bing", "deepl")),
                listOf("google", "bing", "deepl"), listOf("google", "deepl", "bing"), true,
            ),
        )
        cases.forEach { case ->
            assertEquals(case.effective, case.config.effectiveTranslatorIds(case.installed), case.name)
            assertEquals(case.eligible, case.config.isComparisonEligible(case.installed), case.name)
        }
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

    private class FakeTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse(key))
    }
}

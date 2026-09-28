package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServicePresetDefaultsTest {

    /** The roles ServicePreset.createDefault() deliberately assigns a default for. Summarizer,
     *  Rewriter and Image Search are intentionally left unassigned -- there is no zero-config
     *  bundled vendor to default them to the way there is for these five. */
    private val defaultedRoles = listOf(
        ServiceRole.TRANSLATOR,
        ServiceRole.TTS,
        ServiceRole.SPELL_CHECKER,
        ServiceRole.OCR,
        ServiceRole.DICTIONARY
    )

    @Test
    fun `fresh preset assigns every defaulted role to a well-formed, distinct-vendor id`() {
        val preset = ServicePreset.createDefault()

        for (role in defaultedRoles) {
            val id = preset.selectedServices[role]
            assertNotNull(id, "No default service id for $role")
            assertNotNull(ServiceId.parse(id), "Default id for $role is not a well-formed pluginId:instanceId:serviceKey -> $id")
        }

        // A deliberate per-role choice, not "default everything to one vendor": at least two
        // different plugins must be represented across the five roles.
        val pluginIds = preset.selectedServices.values.filterNotNull().mapNotNull(ServiceId::pluginIdOf).toSet()
        assertTrue(pluginIds.size > 1, "Expected more than one plugin across default role choices, got: $pluginIds")
    }

    @Test
    fun `fresh preset does not default OCR, spell checking or dictionary to a Google service that requires setup or is architecturally brittle`() {
        val preset = ServicePreset.createDefault()

        // Google OCR only registers once a Vision API key is configured, so defaulting to it would
        // pick a service that is never actually present on a fresh install. Google's Dictionary and
        // Spell Checker both piggyback on the same unofficial, no-fallback translate endpoint.
        assertTrue(
            ServiceId.pluginIdOf(preset.selectedServices[ServiceRole.OCR]!!) != "google-services",
            "OCR should not default to google-services (never registered without a Vision key)"
        )
        assertTrue(
            ServiceId.pluginIdOf(preset.selectedServices[ServiceRole.SPELL_CHECKER]!!) != "google-services",
            "Spell Checker should not default to google-services's brittle, fallback-less endpoint"
        )
        assertTrue(
            ServiceId.pluginIdOf(preset.selectedServices[ServiceRole.DICTIONARY]!!) != "google-services",
            "Dictionary should not default to google-services's brittle, fallback-less endpoint"
        )
    }

    @Test
    fun `Configuration DEFAULT is self-consistent and requires no migration`() {
        val config = Configuration.DEFAULT

        assertEquals(config.activeServicePresetId, config.getActivePreset()?.id)
        assertEquals(ConfigMigrator.CURRENT_VERSION, config.configVersion)
        // The sentinel Main.kt's startup path treats as "no explicit choice yet" -- see the
        // comment on Configuration.DEFAULT.interfaceLanguage.
        assertEquals("", config.interfaceLanguage)
    }

    @Test
    fun `resolveActiveServiceId falls back when the preferred default service is unavailable`() {
        val config = Configuration.DEFAULT
        val preferredId = config.getActivePreset()!!.selectedServices[ServiceRole.TRANSLATOR]!!
        val fallbackId = "some-other-plugin:default:some-translator"

        // Preferred service present and usable -> preferred wins.
        assertEquals(
            preferredId,
            config.resolveActiveServiceId(ServiceRole.TRANSLATOR, listOf(fallbackId, preferredId))
        )

        // Preferred service not currently loaded (e.g. its plugin failed to enable) -> falls back
        // to whatever else is usable, rather than resolving to nothing.
        assertEquals(
            fallbackId,
            config.resolveActiveServiceId(ServiceRole.TRANSLATOR, listOf(fallbackId))
        )

        // Nothing usable at all -> no active service, not a crash.
        assertNull(config.resolveActiveServiceId(ServiceRole.TRANSLATOR, emptyList()))
    }

    @Test
    fun `an existing explicit user selection is preserved over the factory default`() {
        val explicitChoice = "bing-services:default:bing-translator"
        val customPreset = ServicePreset.createDefault().copy(
            selectedServices = ServicePreset.createDefault().selectedServices +
                (ServiceRole.TRANSLATOR to explicitChoice)
        )
        val config = Configuration.DEFAULT.copy(
            servicePresets = listOf(customPreset),
            activeServicePresetId = customPreset.id
        )

        val defaultTranslatorId = ServicePreset.createDefault().selectedServices[ServiceRole.TRANSLATOR]!!
        assertEquals(
            explicitChoice,
            config.resolveActiveServiceId(ServiceRole.TRANSLATOR, listOf(explicitChoice, defaultTranslatorId))
        )
    }
}

package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun `fresh preset assigns every defaulted role a well-formed service id`() {
        val preset = ServicePreset.createDefault()

        for (role in defaultedRoles) {
            val id = preset.selectedServices[role]
            assertNotNull(id, "No default service id for $role")
            assertNotNull(ServiceId.parse(id), "Default id for $role is not a well-formed pluginId:instanceId:serviceKey -> $id")
        }
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

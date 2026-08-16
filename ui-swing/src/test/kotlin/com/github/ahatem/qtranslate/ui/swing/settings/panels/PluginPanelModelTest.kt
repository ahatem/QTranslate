package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.plugin.PluginManifest
import com.github.ahatem.qtranslate.core.plugin.PluginState
import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginPanelModelTest {
    @Test
    fun `disabled plugins retain categories and can be filtered`() {
        val plugin = plugin("DeepL Services", "DeepL", PluginStatus.DISABLED, TranslatorStub)

        assertEquals(setOf(PluginCategory.TRANSLATORS), PluginPanelModel.categories(plugin))
        assertEquals(listOf(plugin), PluginPanelModel.filter(listOf(plugin), "", PluginCategory.TRANSLATORS))
    }

    @Test
    fun `search matches author description id and service name`() {
        val plugin = plugin("Reference", "Wikimedia", PluginStatus.ENABLED, TranslatorStub)

        listOf("wikimedia", "reference", "sample plugin", "test.plugin", "translator stub").forEach { query ->
            assertEquals(listOf(plugin), PluginPanelModel.filter(listOf(plugin), query, PluginCategory.ALL))
        }
    }

    private fun plugin(name: String, author: String, status: PluginStatus, service: Service) = PluginState(
        manifest = PluginManifest("test.plugin", name, "1.0.0", author, "Sample plugin", "1.0.0"),
        status = status,
        jarPath = "plugin.jar",
        services = listOf(service)
    )

    private object TranslatorStub : Translator {
        override val key = "translator-stub"
        override val name = "Translator Stub"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH))
        override suspend fun translate(request: com.github.ahatem.qtranslate.api.translator.TranslationRequest) =
            error("Not used")
    }
}

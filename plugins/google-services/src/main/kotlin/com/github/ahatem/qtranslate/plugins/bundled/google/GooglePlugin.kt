package com.github.ahatem.qtranslate.plugins.bundled.google


import com.github.ahatem.qtranslate.api.*
import com.github.ahatem.qtranslate.plugins.bundled.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class GooglePlugin : Plugin {
    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private lateinit var apiConfig: ApiConfig

    override fun initialize(context: PluginContext): Result<List<Service>, ServiceError> {
        this.pluginContext = context

        val settings = GoogleSettings().apply {
            translateApiKey = context.getSecret("translateApiKey") ?: ""
            visionApiKey = context.getSecret("visionApiKey") ?: ""
        }

        apiConfig = ApiConfig()
        httpClient = KtorHttpClient(pluginContext)

        pluginContext.logInfo("Google Services Plugin initialized successfully")

        return Ok(
            buildList {
                val languageMapper = GoogleLanguageMapper
                add(GoogleTranslatorService(pluginContext, settings, httpClient, languageMapper, apiConfig))
                add(GoogleTTSService(pluginContext, httpClient, languageMapper, apiConfig))
                add(GoogleDictionaryService(pluginContext, httpClient, languageMapper, apiConfig))
                add(GoogleOCRService(pluginContext, settings, httpClient, languageMapper, apiConfig))
                add(GoogleSpellCheckerService(pluginContext, httpClient, languageMapper, apiConfig))
            }
        )
    }

    override fun getSettingsClass(): Class<*> {
        return GoogleSettings::class.java
    }

    override fun shutdown() {
        pluginContext.logInfo("Google Services Plugin shutting down")
        httpClient.close()
    }
}

class GoogleSettings {
    @Setting(
        label = "Translation API Key",
        description = "Optional: Google Cloud Translation API key for higher quotas.",
        type = SettingType.PASSWORD,
        order = 1
    )
    var translateApiKey: String = ""

    @Setting(
        label = "Vision API Key",
        description = "Required: Google Cloud Vision API key for OCR (text recognition).",
        type = SettingType.PASSWORD,
        isRequired = true,
        order = 2
    )
    var visionApiKey: String = ""
}
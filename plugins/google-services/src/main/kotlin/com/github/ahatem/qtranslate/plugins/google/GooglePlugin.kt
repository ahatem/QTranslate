package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.*
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class GooglePlugin : Plugin {
    private lateinit var pluginContext: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private lateinit var apiConfig: ApiConfig
    private lateinit var settings: GoogleSettings

    override fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context

        settings = GoogleSettings().apply {
            translateApiKey = context.getSecret("translateApiKey") ?: ""
            visionApiKey = context.getSecret("visionApiKey") ?: ""
        }

        apiConfig = ApiConfig()
        httpClient = KtorHttpClient(pluginContext)

        pluginContext.logInfo("Google Services Plugin initialized successfully")

        return Ok(Unit)
    }

    override fun getServices(): List<Service> {
        return buildList {
            val languageMapper = GoogleLanguageMapper
            add(GoogleTranslatorService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            add(GoogleTTSService(pluginContext, httpClient, languageMapper, apiConfig))
            add(GoogleDictionaryService(pluginContext, httpClient, languageMapper, apiConfig))
            add(GoogleOCRService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            add(GoogleSpellCheckerService(pluginContext, httpClient, languageMapper, apiConfig))
        }
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
        label = "Vision API Key (for OCR)",
        description = "Your personal Google Cloud Vision API key. This is required for text recognition from images (OCR).",
        type = SettingType.PASSWORD,
        isRequired = true,
        order = 1
    )
    var visionApiKey: String = ""

    @Setting(
        label = "Translation API Key (Optional)",
        description = "An optional Google Cloud Translation API key. If provided, it may grant higher usage quotas.",
        type = SettingType.PASSWORD,
        order = 2
    )
    var translateApiKey: String = ""
}
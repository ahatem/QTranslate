package com.github.ahatem.qtranslate.plugins.deepl

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class DeepLPlugin : Plugin<DeepLSettings> {
    private lateinit var context: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private var settings = DeepLSettings()
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        settings = DeepLSettings(
            apiKey = context.getValue(KEY_API_KEY).orEmpty(),
            apiTier = context.getValue(KEY_API_TIER) ?: TIER_FREE
        )
        httpClient = KtorHttpClient(context)
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        services = listOf(DeepLTranslatorService(context, httpClient) { settings })
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: DeepLSettings): Result<Unit, ServiceError> {
        if (settings.apiKey.isBlank()) {
            return Err(ServiceError.ValidationError("DeepL API key must not be empty."))
        }
        if (settings.apiTier !in setOf(TIER_FREE, TIER_PRO)) {
            return Err(ServiceError.ValidationError("DeepL API tier must be Free or Pro."))
        }

        context.storeValue(KEY_API_KEY, settings.apiKey.trim())
        context.storeValue(KEY_API_TIER, settings.apiTier)
        this.settings = settings.copy(apiKey = settings.apiKey.trim())
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        services = emptyList()
    }

    override suspend fun shutdown() {
        httpClient.close()
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): DeepLSettings = settings

    private companion object {
        const val KEY_API_KEY = "apiKey"
        const val KEY_API_TIER = "apiTier"
    }
}

data class DeepLSettings(
    @field:Setting(
        label = "API Key",
        description = "Create an API key in your DeepL account. The API Free and DeepL Translator subscriptions use different credentials.",
        type = SettingType.PASSWORD,
        isRequired = true,
        order = 10
    )
    var apiKey: String = "",

    @field:Setting(
        label = "API Tier",
        description = "Choose Free for keys ending in :fx; choose Pro for paid DeepL API plans.",
        type = SettingType.DROPDOWN,
        options = "Free,Pro",
        defaultValue = "Free",
        order = 20
    )
    var apiTier: String = TIER_FREE
) : PluginSettings.Configurable() {
    internal fun baseUrl(): String =
        if (apiTier == TIER_PRO) "https://api.deepl.com" else "https://api-free.deepl.com"

    internal fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "DeepL-Auth-Key $apiKey"
    )
}

private const val TIER_FREE = "Free"
private const val TIER_PRO = "Pro"

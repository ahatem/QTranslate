package com.github.ahatem.qtranslate.plugins.mozhi

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
import java.net.URI

class MozhiPlugin : Plugin<MozhiSettings> {
    private lateinit var context: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private var settings = MozhiSettings()
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        settings = MozhiSettings(
            instanceUrl = context.getValue(KEY_INSTANCE_URL) ?: DEFAULT_INSTANCE_URL,
            engine = context.getValue(KEY_ENGINE) ?: DEFAULT_ENGINE
        )
        httpClient = KtorHttpClient(context)
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        services = listOf(MozhiTranslatorService(context, httpClient) { settings })
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: MozhiSettings): Result<Unit, ServiceError> {
        val normalizedUrl = settings.normalizedInstanceUrl()
        val uri = runCatching { URI(normalizedUrl) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return Err(ServiceError.ValidationError("Mozhi instance URL must be a valid HTTP or HTTPS URL."))
        }
        if (settings.engine !in SUPPORTED_ENGINES) {
            return Err(ServiceError.ValidationError("Select a supported Mozhi engine."))
        }

        context.storeValue(KEY_INSTANCE_URL, normalizedUrl)
        context.storeValue(KEY_ENGINE, settings.engine)
        this.settings = settings.copy(instanceUrl = normalizedUrl)
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        services = emptyList()
    }

    override suspend fun shutdown() {
        httpClient.close()
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): MozhiSettings = settings

    private companion object {
        const val KEY_INSTANCE_URL = "instanceUrl"
        const val KEY_ENGINE = "engine"
    }
}

data class MozhiSettings(
    @field:Setting(
        label = "Instance URL",
        description = "Base URL of a public or self-hosted Mozhi instance.",
        type = SettingType.TEXT,
        isRequired = true,
        defaultValue = DEFAULT_INSTANCE_URL,
        order = 10
    )
    var instanceUrl: String = DEFAULT_INSTANCE_URL,

    @field:Setting(
        label = "Translation Engine",
        description = "The upstream engine Mozhi should use.",
        type = SettingType.DROPDOWN,
        options = "Google,DeepL,DuckDuckGo,LibreTranslate,MyMemory,Reverso,Yandex",
        defaultValue = DEFAULT_ENGINE,
        order = 20
    )
    var engine: String = DEFAULT_ENGINE
) : PluginSettings.Configurable() {
    internal fun normalizedInstanceUrl(): String = instanceUrl.trim().trimEnd('/')
}

private const val DEFAULT_INSTANCE_URL = "https://mozhi.aryak.me"
private const val DEFAULT_ENGINE = "Google"
private val SUPPORTED_ENGINES = setOf(
    "Google", "DeepL", "DuckDuckGo", "LibreTranslate", "MyMemory", "Reverso", "Yandex"
)

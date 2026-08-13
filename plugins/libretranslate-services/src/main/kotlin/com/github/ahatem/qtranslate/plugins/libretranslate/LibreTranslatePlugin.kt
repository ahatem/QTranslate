package com.github.ahatem.qtranslate.plugins.libretranslate

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import java.net.URI

class LibreTranslatePlugin : Plugin<LibreTranslateSettings> {
    private lateinit var context: PluginContext
    private lateinit var httpClient: KtorHttpClient
    private var settings = LibreTranslateSettings()
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        settings = LibreTranslateSettings(
            instanceUrl = context.getValue(KEY_INSTANCE_URL) ?: DEFAULT_INSTANCE_URL,
            apiKey = context.getValue(KEY_API_KEY).orEmpty()
        )
        httpClient = KtorHttpClient(context)
        settings.attach(context) { httpClient }
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        services = listOf(LibreTranslateService(context, httpClient) { settings })
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: LibreTranslateSettings): Result<Unit, ServiceError> {
        val normalizedUrl = settings.normalizedInstanceUrl()
        val uri = runCatching { URI(normalizedUrl) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return Err(ServiceError.ValidationError("LibreTranslate URL must be a valid HTTP or HTTPS URL."))
        }

        context.storeValue(KEY_INSTANCE_URL, normalizedUrl)
        context.storeValue(KEY_API_KEY, settings.apiKey.trim())
        this.settings = settings.copy(instanceUrl = normalizedUrl, apiKey = settings.apiKey.trim())
            .attach(context) { httpClient }
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        services = emptyList()
    }

    override suspend fun shutdown() {
        services = emptyList()
        httpClient.close()
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): LibreTranslateSettings = settings

    private companion object {
        const val KEY_INSTANCE_URL = "instanceUrl"
        const val KEY_API_KEY = "apiKey"
    }
}

data class LibreTranslateSettings(
    @field:Setting(
        label = "Server URL",
        description = "Address of the local or self-hosted LibreTranslate server.",
        type = SettingType.TEXT,
        isRequired = true,
        defaultValue = DEFAULT_INSTANCE_URL,
        order = 10
    )
    var instanceUrl: String = DEFAULT_INSTANCE_URL,

    @field:Setting(
        label = "API Key",
        description = "Optional. Leave blank for a local server that does not require authentication.",
        type = SettingType.PASSWORD,
        order = 20
    )
    var apiKey: String = ""
) : PluginSettings.Configurable() {
    @Transient private var context: PluginContext? = null
    @Transient private var clientProvider: (() -> KtorHttpClient)? = null

    internal fun attach(
        context: PluginContext,
        clientProvider: () -> KtorHttpClient
    ): LibreTranslateSettings = apply {
        this.context = context
        this.clientProvider = clientProvider
    }

    internal fun normalizedInstanceUrl(): String = instanceUrl.trim().trimEnd('/')

    @PluginAction(
        label = "Test Connection",
        order = 30,
        tooltip = "Tests the saved LibreTranslate server and API key."
    )
    fun testConnection(): String = runBlocking {
        val context = context ?: return@runBlocking "LibreTranslate is not initialized."
        val client = clientProvider?.invoke() ?: return@runBlocking "LibreTranslate is not initialized."
        LibreTranslateService(context, client) { this@LibreTranslateSettings }
            .translate(TranslationRequest("hello", LanguageCode.ENGLISH, LanguageCode.FRENCH))
            .fold(
                success = { "Connected successfully." },
                failure = { "Connection failed: ${it.message}" }
            )
    }
}

private const val DEFAULT_INSTANCE_URL = "http://localhost:5000"

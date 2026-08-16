package com.github.ahatem.qtranslate.plugins.deepl

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlinx.coroutines.runBlocking

class DeepLPlugin : Plugin<DeepLSettings> {
    private lateinit var context: PluginContext
    private val httpClient: HttpClient get() = context.http
    private var settings = DeepLSettings()
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        settings = DeepLSettings(
            apiKey = context.secrets.get(KEY_API_KEY).orEmpty()
        )
        settings.updateMode(if (settings.apiKey.isBlank()) DeepLMode.FREE_WEB else DeepLMode.OFFICIAL)
        settings.attach(context) { httpClient }
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        services = listOf(DeepLTranslatorService(
            context = context,
            httpClient = httpClient,
            settings = { settings },
            onModeChanged = settings::updateMode
        ))
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: DeepLSettings): Result<Unit, ServiceError> {
        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) context.secrets.remove(KEY_API_KEY) else context.secrets.put(KEY_API_KEY, apiKey)
        this.settings = settings.copy(apiKey = apiKey).also {
            it.updateMode(if (apiKey.isBlank()) DeepLMode.FREE_WEB else DeepLMode.OFFICIAL)
            it.attach(context) { httpClient }
        }
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        services = emptyList()
    }

    override suspend fun shutdown() {
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): DeepLSettings = settings

    private companion object {
        const val KEY_API_KEY = "apiKey"
    }
}

data class DeepLSettings(
    @field:Setting(
        label = "API Key",
        description = "Optional. Add a DeepL API key to use the official API; leave blank to use the free web endpoint.",
        type = SettingType.PASSWORD,
        order = 10
    )
    var apiKey: String = "",

    @field:Setting(
        label = "Current mode",
        description = "The mode changes automatically when the API key is saved.",
        type = SettingType.CUSTOM_PANEL,
        actionMethod = "createModePanel",
        order = 20
    )
    var modePanel: String = ""
) : PluginSettings.Configurable() {
    private var mode: DeepLMode = if (apiKey.isBlank()) DeepLMode.FREE_WEB else DeepLMode.OFFICIAL
    @Transient private var context: PluginContext? = null
    @Transient private var clientProvider: (() -> HttpClient)? = null

    internal fun attach(context: PluginContext, clientProvider: () -> HttpClient) {
        this.context = context
        this.clientProvider = clientProvider
    }

    internal fun baseUrl(): String =
        if (apiKey.endsWith(":fx", ignoreCase = true)) "https://api-free.deepl.com" else "https://api.deepl.com"

    internal fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "DeepL-Auth-Key $apiKey"
    )

    internal fun updateMode(mode: DeepLMode) {
        this.mode = mode
    }

    @PluginAction(
        label = "Test Connection",
        order = 30,
        tooltip = "Tests the saved API key or the free endpoint."
    )
    fun testConnection(): String = runBlocking {
        val context = context ?: return@runBlocking "DeepL is not initialized."
        val client = clientProvider?.invoke() ?: return@runBlocking "DeepL is not initialized."
        DeepLTranslatorService(context, client, { this@DeepLSettings }, minimumWebRequestIntervalMillis = 0)
            .translate(TranslationRequest("hello", LanguageCode.ENGLISH, LanguageCode.FRENCH))
            .fold(
                success = {
                    "Connected successfully using ${if (apiKey.isBlank()) "the free endpoint" else "the official API"}."
                },
                failure = { "Connection failed: ${it.message}" }
            )
    }

    @Suppress("unused")
    private fun createModePanel(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JLabel(mode.html), BorderLayout.CENTER)
    }
}

internal enum class DeepLMode(val html: String) {
    OFFICIAL("<html><b>Using official API</b></html>"),
    FREE_WEB(
        "<html><b>Using free web endpoint</b><br>" +
            "<font color='#888888'>Free endpoint - may be slow or stop working. " +
            "Add an API key for official access.</font></html>"
    ),
    FREE_WEB_AFTER_REJECTION(
        "<html><b>Using free web endpoint</b><br>" +
            "<font color='#888888'>The saved API key was rejected. Update it to restore official access.</font></html>"
    )
}

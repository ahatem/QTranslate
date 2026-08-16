package com.github.ahatem.qtranslate.plugins.mozhi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

class MozhiPlugin : Plugin<MozhiSettings> {
    private lateinit var context: PluginContext
    private val httpClient: HttpClient get() = context.http
    private var settings = MozhiSettings()
    private var services: List<Service> = emptyList()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.context = context
        val savedInstance = context.settings.getString(KEY_INSTANCE_URL) ?: DEFAULT_INSTANCE_URL
        val savedCustomInstance = context.settings.getString(KEY_CUSTOM_INSTANCE_URL).orEmpty()
        settings = MozhiSettings(
            instanceUrl = savedInstance.takeIf { it in PUBLIC_INSTANCES } ?: CUSTOM_INSTANCE,
            customInstanceUrl = savedCustomInstance.ifBlank {
                savedInstance.takeUnless { it in PUBLIC_INSTANCES || it in LEGACY_CUSTOM_VALUES }.orEmpty()
            },
            engine = context.settings.getString(KEY_ENGINE) ?: DEFAULT_ENGINE
        ).attach(context) { httpClient }
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        services = listOf(MozhiTranslatorService(context, httpClient) { settings })
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: MozhiSettings): Result<Unit, ServiceError> {
        val normalizedUrl = settings.resolvedInstanceUrl()
        val uri = runCatching { URI(normalizedUrl) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return Err(ServiceError.ValidationError("Mozhi instance URL must be a valid HTTP or HTTPS URL."))
        }
        if (settings.engine !in SUPPORTED_ENGINES) {
            return Err(ServiceError.ValidationError("Select a supported Mozhi engine."))
        }

        context.settings.put(KEY_INSTANCE_URL, settings.instanceUrl)
        context.settings.put(KEY_CUSTOM_INSTANCE_URL, settings.customInstanceUrl.trim())
        context.settings.put(KEY_ENGINE, settings.engine)
        this.settings = settings.copy(
            instanceUrl = settings.instanceUrl,
            customInstanceUrl = settings.customInstanceUrl.trim()
        ).attach(context) { httpClient }
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        services = emptyList()
    }

    override suspend fun shutdown() {
    }

    override fun getServices(): List<Service> = services
    override fun getSettings(): MozhiSettings = settings

    private companion object {
        const val KEY_INSTANCE_URL = "instanceUrl"
        const val KEY_CUSTOM_INSTANCE_URL = "customInstanceUrl"
        const val KEY_ENGINE = "engine"
    }
}

data class MozhiSettings(
    @field:Setting(
        label = "Public Instance",
        description = "Choose an instance maintained by the Mozhi community, or Self-hosted for your own server.",
        type = SettingType.DROPDOWN,
        options = MOZHI_INSTANCE_OPTIONS,
        isRequired = true,
        defaultValue = DEFAULT_INSTANCE_URL,
        order = 10
    )
    var instanceUrl: String = DEFAULT_INSTANCE_URL,

    @field:Setting(
        label = "Self-hosted Instance URL",
        description = "Only needed when Public Instance is set to Self-hosted.",
        type = SettingType.TEXT,
        defaultValue = "",
        showIf = "instanceUrl=Self-hosted",
        order = 15
    )
    var customInstanceUrl: String = "",

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
    @Transient private var context: PluginContext? = null
    @Transient private var clientProvider: (() -> HttpClient)? = null

    internal fun attach(context: PluginContext, clientProvider: () -> HttpClient): MozhiSettings = apply {
        this.context = context
        this.clientProvider = clientProvider
    }

    internal fun resolvedInstanceUrl(): String =
        (if (instanceUrl == CUSTOM_INSTANCE) customInstanceUrl else instanceUrl).trim().trimEnd('/')

    @PluginAction(
        label = "Test Selected Instance",
        order = 30,
        tooltip = "Translates a short phrase using the saved instance."
    )
    fun testSelectedInstance(): String = runBlocking {
        val elapsed = measureEndpoint(resolvedInstanceUrl())
        if (elapsed != null) "Connected successfully in ${elapsed} ms." else
            "Connection failed. Select another public instance or test all endpoints."
    }

    @PluginAction(
        label = "Test All Endpoints",
        order = 40,
        tooltip = "Tests public instances and selects the fastest working one."
    )
    fun testAllEndpoints(): String = runBlocking {
        val results = supervisorScope {
            PUBLIC_INSTANCES.map { endpoint -> async { endpoint to measureEndpoint(endpoint) } }.awaitAll()
        }
        val working = results.filter { it.second != null }.sortedBy { it.second }
        val fastest = working.firstOrNull()
            ?: return@runBlocking "No public Mozhi instance responded. Check your network and try again later."
        instanceUrl = fastest.first
        context?.settings?.put(MOZHI_INSTANCE_STORAGE_KEY, fastest.first)
        buildString {
            append("Selected ${fastest.first} (${fastest.second} ms).")
            working.drop(1).forEach { (url, millis) -> append("\n$url: $millis ms") }
            val failed = results.count { it.second == null }
            if (failed > 0) append("\n$failed endpoint(s) did not respond.")
        }
    }

    private suspend fun measureEndpoint(endpoint: String): Long? {
        val context = context ?: return null
        val client = clientProvider?.invoke() ?: return null
        val started = System.nanoTime()
        val result = runCatching {
            withTimeout(8_000) {
                MozhiTranslatorService(context, client) {
                    copy(instanceUrl = endpoint, customInstanceUrl = "")
                }.translate(TranslationRequest("hello", LanguageCode.ENGLISH, LanguageCode.FRENCH))
            }
        }.getOrNull() ?: return null
        return result.fold(
            success = { (System.nanoTime() - started) / 1_000_000 },
            failure = { null }
        )
    }
}

private const val CUSTOM_INSTANCE = "Self-hosted"
private const val MOZHI_INSTANCE_STORAGE_KEY = "instanceUrl"
private val LEGACY_CUSTOM_VALUES = setOf("Custom", CUSTOM_INSTANCE)
private const val MOZHI_INSTANCE_OPTIONS = "https://mozhi.aryak.me,https://translate.projectsegfau.lt,https://translate.nerdvpn.de,https://mozhi.ducks.party,https://mozhi.pussthecat.org,https://mozhi.adminforge.de,https://translate.privacyredirect.com,https://mozhi.canine.tools,https://mzh.dc09.ru,https://mozhi.franklyflawless.org,Self-hosted"
private val PUBLIC_INSTANCES = MOZHI_INSTANCE_OPTIONS.split(',').filterNot { it == CUSTOM_INSTANCE }
private const val DEFAULT_INSTANCE_URL = "https://mozhi.adminforge.de"
private const val DEFAULT_ENGINE = "Google"
private val SUPPORTED_ENGINES = setOf(
    "Google", "DeepL", "DuckDuckGo", "LibreTranslate", "MyMemory", "Reverso", "Yandex"
)

package com.github.ahatem.qtranslate.core.ocr

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.isServiceDisabled
import com.github.ahatem.qtranslate.core.settings.data.isServiceRoleEnabled
import com.github.ahatem.qtranslate.core.shared.util.hasRole
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** UI-facing metadata for one usable OCR provider. */
data class OcrProviderInfo(
    val id: String,
    val name: String,
    val iconPath: String?,
    val languages: List<LanguageCode>,
    val isLoadingLanguages: Boolean,
)

/**
 * The OCR providers currently offered by loaded plugins, with their declared languages.
 *
 * This is deliberately a read-only projection of plugin state rather than a second registry.
 * Services are filtered through the same role/disabled rules as [ActiveServiceManager], so the
 * workspace cannot offer a provider the rest of the application would refuse to use.
 *
 * Dynamic language lists are fetched once per service and cached. Until a fetch completes the
 * provider is still selectable; its language control simply says it is not loaded yet.
 */
class OcrServiceCatalog(
    private val activeServices: StateFlow<Map<String, Service>>,
    private val configuration: StateFlow<Configuration>,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val dynamicLanguages = MutableStateFlow<Map<String, Set<LanguageCode>>>(emptyMap())
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    val providers: StateFlow<List<OcrProviderInfo>> =
        combine(activeServices, configuration, dynamicLanguages) { services, config, cached ->
            if (!config.isServiceRoleEnabled(ServiceRole.OCR)) {
                emptyList()
            } else {
                buildProviders(services, config, cached)
            }
        }.stateIn(
            scope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            buildProviders(activeServices.value, configuration.value, emptyMap()),
        )

    /** Languages known for [serviceId] right now. */
    fun languagesFor(serviceId: String): List<LanguageCode> =
        providers.value.firstOrNull { it.id == serviceId }?.languages.orEmpty()

    private fun buildProviders(
        services: Map<String, Service>,
        config: Configuration,
        cached: Map<String, Set<LanguageCode>>,
    ): List<OcrProviderInfo> = services.entries
        .asSequence()
        .filter { (id, service) ->
            service.hasRole(ServiceRole.OCR) && !config.isServiceDisabled(id, ServiceRole.OCR)
        }
        .map { (id, service) ->
            val dynamic = service.supportedLanguages is SupportedLanguages.Dynamic
            val loaded = !dynamic || cached.containsKey(id)
            val languages = when (val supported = service.supportedLanguages) {
                is SupportedLanguages.All -> COMMON_OCR_LANGUAGES
                is SupportedLanguages.Specific -> supported.languages.sortedBy { it.tag }
                SupportedLanguages.Dynamic ->
                    cached[id]?.sortedBy { it.tag }.orEmpty()
            }
            if (dynamic && !loaded) requestLanguages(id, service)
            OcrProviderInfo(
                id = id,
                name = service.name,
                iconPath = service.iconPath,
                languages = languages,
                isLoadingLanguages = dynamic && !loaded,
            )
        }
        .sortedBy { it.name.lowercase() }
        .toList()

    private fun requestLanguages(serviceId: String, service: Service) {
        if (!fetching.add(serviceId)) return
        scope.launch {
            val languages = runCatching { service.fetchSupportedLanguages() }
                .getOrNull()
                ?.fold(success = { it }, failure = { emptySet() })
                .orEmpty()
            dynamicLanguages.value = dynamicLanguages.value + (serviceId to languages)
            fetching.remove(serviceId)
            logger.debug("Cached ${languages.size} OCR language(s) for '${service.name}'")
        }
    }

    private companion object {
        /**
         * A broad list for providers that declare [SupportedLanguages.All]. It mirrors the
         * translator picker's practical set rather than pretending the provider accepts every
         * arbitrary tag.
         */
        val COMMON_OCR_LANGUAGES: List<LanguageCode> = listOf(
            LanguageCode.AUTO,
            LanguageCode.ENGLISH,
            LanguageCode.CHINESE_SIMPLIFIED,
            LanguageCode.CHINESE_TRADITIONAL,
            LanguageCode.HINDI,
            LanguageCode.SPANISH,
            LanguageCode.FRENCH,
            LanguageCode.ARABIC,
            LanguageCode.BENGALI,
            LanguageCode.RUSSIAN,
            LanguageCode.PORTUGUESE_BRAZIL,
            LanguageCode.PORTUGUESE_PORTUGAL,
            LanguageCode.INDONESIAN,
            LanguageCode.URDU,
            LanguageCode.GERMAN,
            LanguageCode.JAPANESE,
            LanguageCode.SWAHILI,
            LanguageCode.TURKISH,
            LanguageCode.VIETNAMESE,
            LanguageCode.KOREAN,
            LanguageCode.ITALIAN,
            LanguageCode.THAI,
            LanguageCode.FARSI,
            LanguageCode.POLISH,
            LanguageCode.UKRAINIAN,
            LanguageCode.DUTCH,
            LanguageCode.GREEK,
            LanguageCode.HEBREW,
            LanguageCode.HUNGARIAN,
            LanguageCode.CZECH,
            LanguageCode.SWEDISH,
            LanguageCode.ROMANIAN,
            LanguageCode.DANISH,
            LanguageCode.FINNISH,
        )
    }
}

/**
 * Resolves a configured OCR language tag against a provider. Blank means "follow translation
 * source", which preserves the historical behavior; "auto" is forwarded to providers that
 * support detection. An unsupported explicit language is still forwarded so the provider can
 * report a proper typed error rather than the host silently substituting another language.
 */
fun resolveOcrLanguage(
    configuredTag: String,
    translationSource: LanguageCode,
    providerLanguages: List<LanguageCode> = emptyList(),
): LanguageCode {
    if (configuredTag.isBlank()) return translationSource
    if (configuredTag.equals(LanguageCode.AUTO.tag, ignoreCase = true)) return LanguageCode.AUTO
    val exact = providerLanguages.firstOrNull { it.tag.equals(configuredTag, ignoreCase = true) }
    return exact ?: LanguageCode(configuredTag)
}

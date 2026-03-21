package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceSelectionState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.util.mapServiceToType
import com.github.michaelbull.result.getOr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Observes available services and the languages supported by the active translator.
 *
 * ### Language resolution
 * [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages] has three variants:
 * - [SupportedLanguages.All] — the service supports every language; we emit a well-known
 *   broad set rather than an empty list so the UI has something to display.
 * - [SupportedLanguages.Specific] — languages are known statically; used directly.
 * - [SupportedLanguages.Dynamic] — languages must be fetched via a suspend call.
 *   Since [combine] is non-suspending, the fetch is launched as a side-effect on [scope]
 *   and the result is stored in [dynamicLanguageCache]. The [combine] reads from the
 *   cache, which starts empty (showing a loading/empty state) and fills in once the
 *   fetch completes, triggering a new [combine] emission automatically.
 *
 * @property activeServices Live map of loaded service ID → [Service].
 * @property settingsState Live [Configuration] for preset and disabled-service reads.
 * @property scope Scope for launching Dynamic language fetches.
 */
class SelectActiveServiceUseCase(
    private val activeServices: StateFlow<Map<String, Service>>,
    private val settingsState: StateFlow<Configuration>,
    private val scope: CoroutineScope,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("SelectActiveServiceUseCase")

    // Cache for Dynamic language results, keyed by service ID.
    // MutableStateFlow so that combine() re-emits when the cache updates.
    private val dynamicLanguageCache = MutableStateFlow<Map<String, List<LanguageCode>>>(emptyMap())

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a [Flow] that emits a fresh [ServiceSelectionState] whenever the
     * available services, configuration, or cached language list changes.
     */
    fun observe(): Flow<ServiceSelectionState> =
        combine(activeServices, settingsState, dynamicLanguageCache) { services, config, langCache ->
            val availableServices = services.values
                .filterNot { it.id in config.disabledServices }
                .mapNotNull { toServiceInfo(it) }

            val activePreset = config.getActivePreset() ?: config.servicePresets.firstOrNull()
            val selectedTranslatorId = activePreset?.selectedServices?.get(ServiceType.TRANSLATOR)
            val translator = services[selectedTranslatorId] as? Translator

            val languages = if (translator != null) {
                resolveLanguages(translator, langCache)
            } else {
                emptyList()
            }

            ServiceSelectionState(
                availableServices = availableServices,
                availableLanguages = languages
            )
        }

    /**
     * Returns the supported languages for a specific translator service.
     * Used when the user manually selects a different translator to refresh the
     * language list without waiting for the next [observe] emission.
     */
    suspend fun getLanguagesFor(serviceId: String?): List<LanguageCode> {
        if (serviceId == null) return emptyList()
        val translator = activeServices.value[serviceId] as? Translator ?: return emptyList()
        return resolveLanguagesSuspending(translator)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves languages for [translator] from the cache if available, or launches a
     * background fetch for [SupportedLanguages.Dynamic] and returns empty in the meantime.
     * The [combine] operator will re-emit once the cache is populated.
     */
    private fun resolveLanguages(
        translator: Translator,
        cache: Map<String, List<LanguageCode>>
    ): List<LanguageCode> =
        when (val supported = translator.supportedLanguages) {
            is SupportedLanguages.All      -> LanguageCode.commonLanguages()
            is SupportedLanguages.Specific -> supported.languages.toList()
            is SupportedLanguages.Dynamic  -> {
                val cached = cache[translator.id]
                if (cached != null) {
                    cached
                } else {
                    // Not yet fetched — kick off a fetch and return empty for now.
                    // Once the fetch completes, dynamicLanguageCache updates and
                    // combine() re-emits with the real list.
                    fetchAndCacheDynamicLanguages(translator)
                    emptyList()
                }
            }
        }

    /**
     * Suspending version for direct calls (e.g. [getLanguagesFor]).
     */
    private suspend fun resolveLanguagesSuspending(translator: Translator): List<LanguageCode> =
        when (val supported = translator.supportedLanguages) {
            is SupportedLanguages.All      -> LanguageCode.commonLanguages()
            is SupportedLanguages.Specific -> supported.languages.toList()
            is SupportedLanguages.Dynamic  -> {
                dynamicLanguageCache.value[translator.id]
                    ?: translator.fetchSupportedLanguages()
                        .getOr(emptySet())
                        .toList()
                        .also { languages ->
                            dynamicLanguageCache.value += (translator.id to languages)
                        }
            }
        }

    private fun fetchAndCacheDynamicLanguages(translator: Translator) {
        // Guard: don't re-fetch if already in flight or cached
        if (dynamicLanguageCache.value.containsKey(translator.id)) return

        // Put an empty list as a sentinel so concurrent calls don't double-fetch
        dynamicLanguageCache.value += (translator.id to emptyList())

        scope.launch {
            logger.debug("Fetching dynamic language list for '${translator.name}'")
            val languages = translator.fetchSupportedLanguages()
                .getOr(emptySet())
                .toList()

            dynamicLanguageCache.value += (translator.id to languages)
            logger.debug("Cached ${languages.size} languages for '${translator.name}'")
        }
    }

    private fun toServiceInfo(service: Service): ServiceInfo? {
        val type = mapServiceToType(service) ?: return null
        return ServiceInfo(id = service.id, name = service.name, iconPath = service.iconPath, type = type)
    }
}

/**
 * Returns a broad list of commonly supported languages to display when a service
 * declares [SupportedLanguages.All]. This gives the UI something meaningful to show
 * rather than an infinite or empty list.
 *
 * Defined as an extension on [LanguageCode] companion to keep it co-located with
 * the language constants.
 */
private fun LanguageCode.Companion.commonLanguages(): List<LanguageCode> = listOf(
    AUTO, ENGLISH, CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, HINDI, SPANISH, FRENCH,
    ARABIC, BENGALI, RUSSIAN, PORTUGUESE, INDONESIAN, URDU, GERMAN, JAPANESE,
    SWAHILI, TURKISH, VIETNAMESE, KOREAN, ITALIAN, THAI, FARSI, POLISH, UKRAINIAN,
    DUTCH, GREEK, HEBREW, HUNGARIAN, CZECH, SWEDISH, ROMANIAN, DANISH, FINNISH,
    BULGARIAN, NORWEGIAN, SLOVAK, CATALAN, SERBIAN, CROATIAN, MALAY
)
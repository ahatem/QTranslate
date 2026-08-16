package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceSelectionState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.isServiceDisabled
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.util.roles
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
    private val activeServiceManager: ActiveServiceManager,
    private val scope: CoroutineScope,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("SelectActiveServiceUseCase")

    private companion object {
        /**
         * The roles whose services carry user-facing options today. Reading every role would work
         * but calls the resolver eight times per emission for two answers.
         */
        val OPTION_BEARING_ROLES = listOf(ServiceRole.SUMMARIZER, ServiceRole.REWRITER)
    }

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
            // One entry per role, not per service: a service that both translates and defines
            // words is offered in both pickers, and can be disabled in one without losing the
            // other.
            val availableServices = services.entries.flatMap { (id, service) ->
                service.roles
                    .filterNot { role -> config.isServiceDisabled(id, role) }
                    .map { role ->
                        ServiceInfo(
                            id = id,
                            name = service.name,
                            iconPath = service.iconPath,
                            type = role
                        )
                    }
            }

            val activePreset = config.getActivePreset() ?: config.servicePresets.firstOrNull()
            val selectedTranslatorId = activePreset?.selectedServices?.get(ServiceRole.TRANSLATOR)
            val translator = selectedTranslatorId
                ?.takeUnless { config.isServiceDisabled(it, ServiceRole.TRANSLATOR) }
                ?.let { services[it] as? Translator }

            val languages = if (translator != null && selectedTranslatorId != null) {
                resolveLanguages(selectedTranslatorId, translator, langCache)
            } else {
                emptyList()
            }

            ServiceSelectionState(
                availableServices = availableServices,
                availableLanguages = languages,
                // Options are declared on the service, but a service can hold several roles and
                // an option rarely means anything outside one of them. Without this filter, a
                // service that both summarizes and rewrites would offer summary length while
                // rewriting. An option that names no role applies to all of them.
                serviceOptions = OPTION_BEARING_ROLES.associateWith { role ->
                    activeServiceManager.getActive<Service>(role)?.service?.options
                        ?.filter { it.role == null || it.role == role }
                        .orEmpty()
                }
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
        if (settingsState.value.isServiceDisabled(serviceId, ServiceRole.TRANSLATOR)) return emptyList()
        return resolveLanguagesSuspending(serviceId, translator)
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
        serviceId: String,
        translator: Translator,
        cache: Map<String, List<LanguageCode>>
    ): List<LanguageCode> =
        when (val supported = translator.supportedLanguages) {
            is SupportedLanguages.All      -> LanguageCode.commonLanguages()
            is SupportedLanguages.Specific -> supported.languages.toList()
            is SupportedLanguages.Dynamic  -> {
                val cached = cache[serviceId]
                if (cached != null) {
                    cached
                } else {
                    // Not yet fetched — kick off a fetch and return empty for now.
                    // Once the fetch completes, dynamicLanguageCache updates and
                    // combine() re-emits with the real list.
                    fetchAndCacheDynamicLanguages(serviceId, translator)
                    emptyList()
                }
            }
        }

    /**
     * Suspending version for direct calls (e.g. [getLanguagesFor]).
     */
    private suspend fun resolveLanguagesSuspending(
        serviceId: String,
        translator: Translator
    ): List<LanguageCode> =
        when (val supported = translator.supportedLanguages) {
            is SupportedLanguages.All      -> LanguageCode.commonLanguages()
            is SupportedLanguages.Specific -> supported.languages.toList()
            is SupportedLanguages.Dynamic  -> {
                dynamicLanguageCache.value[serviceId]
                    ?: translator.fetchSupportedLanguages()
                        .getOr(emptySet())
                        .toList()
                        .also { languages ->
                            dynamicLanguageCache.value += (serviceId to languages)
                        }
            }
        }

    private fun fetchAndCacheDynamicLanguages(serviceId: String, translator: Translator) {
        // Guard: don't re-fetch if already in flight or cached
        if (dynamicLanguageCache.value.containsKey(serviceId)) return

        // Put an empty list as a sentinel so concurrent calls don't double-fetch
        dynamicLanguageCache.value += (serviceId to emptyList())

        scope.launch {
            logger.debug("Fetching dynamic language list for '${translator.name}'")
            val languages = translator.fetchSupportedLanguages()
                .getOr(emptySet())
                .toList()

            dynamicLanguageCache.value += (serviceId to languages)
            logger.debug("Cached ${languages.size} languages for '${translator.name}'")
        }
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

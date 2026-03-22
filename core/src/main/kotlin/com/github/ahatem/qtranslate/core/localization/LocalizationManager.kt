package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class LocalizationManager(
    private val appDataDirectory: File,
    private val parser: LanguageTomlParser,
    private val logger: Logger
) {
    private val translationCache  = mutableMapOf<LanguageCode, Map<String, String>>()
    private val languageMetaCache = mutableMapOf<LanguageCode, LocalizedLanguageMeta>()
    private val embeddedFallback: Map<String, String>

    private var activeTranslations: Map<String, String> = emptyMap()

    private val _activeLanguage = MutableStateFlow(LanguageCode.ENGLISH)
    val activeLanguageFlow: StateFlow<LanguageCode> = _activeLanguage.asStateFlow()
    val activeLanguage: LanguageCode get() = _activeLanguage.value

    val isRtl: Boolean
        get() = languageMetaCache[_activeLanguage.value]?.isRtl == true

    val languagesDirectory: File = File(appDataDirectory, "languages").also { it.mkdirs() }

    val availableLanguages: List<String>
        get() = languagesDirectory
            .listFiles { _, name -> name.endsWith(".toml") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    init {
        embeddedFallback = loadEmbeddedFallback()
    }

    // -------------------------------------------------------------------------
    // Language loading — changes the active language
    // -------------------------------------------------------------------------

    suspend fun loadLanguage(languageCode: LanguageCode) {
        withContext(Dispatchers.IO) {
            if (languageCode != LanguageCode.ENGLISH && LanguageCode.ENGLISH !in translationCache) {
                loadAndCacheLanguage(LanguageCode.ENGLISH)
            }
            loadAndCacheLanguage(languageCode)
            activeTranslations    = translationCache[languageCode] ?: emptyMap()
            _activeLanguage.value = languageCode
            logger.debug("Language loaded: ${languageCode.tag}, isRtl=$isRtl")
        }
    }

    // -------------------------------------------------------------------------
    // Meta reading — does NOT change the active language
    // -------------------------------------------------------------------------

    /**
     * Reads the meta section of a language TOML file without changing the active
     * language or affecting [activeLanguageFlow].
     *
     * Use this when you only need display names (e.g. building a language picker
     * list) and don't want to trigger orientation changes or translation switches.
     *
     * Results are cached — repeated calls for the same code are free after the
     * first read.
     */
    suspend fun readLanguageMeta(code: LanguageCode): LocalizedLanguageMeta? {
        return withContext(Dispatchers.IO) {
            // Return from cache if already loaded
            languageMetaCache[code]?.let { return@withContext it }

            runCatching {
                val file = File(languagesDirectory, "${code.tag}.toml")
                if (!file.exists()) return@withContext null
                val parsed = parser.parse(file.readText())
                parsed.meta?.also { languageMetaCache[code] = it }
            }.getOrNull()
        }
    }

    private fun loadAndCacheLanguage(code: LanguageCode) {
        if (code in translationCache) return
        runCatching {
            val file = File(languagesDirectory, "${code.tag}.toml")
            if (!file.exists()) {
                logger.warn("Language file not found for '$code', skipping")
                return
            }
            val parsed = parser.parse(file.readText())
            translationCache[code]    = parsed.entries
            parsed.meta?.let { languageMetaCache[code] = it }
        }.onFailure {
            logger.error("Failed to load language file: $code", it)
        }
    }

    // -------------------------------------------------------------------------
    // String resolution
    // -------------------------------------------------------------------------

    fun getString(key: String, vararg args: Any): String {
        val raw = activeTranslations[key]
            ?: translationCache[LanguageCode.ENGLISH]?.get(key)
            ?: embeddedFallback[key]
            ?: key
        return if (args.isEmpty()) raw else raw.format(*args)
    }

    fun getLanguageMeta(language: LanguageCode): LocalizedLanguageMeta? =
        languageMetaCache[language]

    fun clearCache() {
        translationCache.clear()
        languageMetaCache.clear()
        activeTranslations    = emptyMap()
        _activeLanguage.value = LanguageCode.ENGLISH
    }

    // -------------------------------------------------------------------------
    // Embedded fallback
    // -------------------------------------------------------------------------

    private fun loadEmbeddedFallback(): Map<String, String> =
        runCatching {
            val stream = checkNotNull(
                this::class.java.classLoader.getResourceAsStream("localization/embedded_en.toml")
            ) { "Missing embedded localization file: localization/embedded_en.toml" }
            parser.parse(stream.bufferedReader().readText()).entries
        }.getOrElse {
            logger.error("Failed to load embedded fallback localization", it)
            emptyMap()
        }
}
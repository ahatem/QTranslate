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

    // StateFlow so MainAppFrame (and any other observer) can react to language
    // changes without polling. Emits on every successful loadLanguage() call.
    private val _activeLanguage = MutableStateFlow(LanguageCode.ENGLISH)
    val activeLanguageFlow: StateFlow<LanguageCode> = _activeLanguage.asStateFlow()
    val activeLanguage: LanguageCode get() = _activeLanguage.value

    /**
     * Whether the currently active language is right-to-left.
     * Reads from the `[meta] rtl` field in the language TOML file.
     * Falls back to `false` if the language has no meta section.
     */
    val isRtl: Boolean
        get() = languageMetaCache[_activeLanguage.value]?.isRtl == true

    val languagesDirectory: File = File(appDataDirectory, "languages").also { it.mkdirs() }

    /**
     * Returns the list of available language codes from the languages directory.
     *
     * Computed on every call rather than cached at construction time, so that
     * language files added after startup are visible without restarting.
     */
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
    // Language loading
    // -------------------------------------------------------------------------

    suspend fun loadLanguage(languageCode: LanguageCode) {
        withContext(Dispatchers.IO) {
            if (languageCode != LanguageCode.ENGLISH && LanguageCode.ENGLISH !in translationCache) {
                loadAndCacheLanguage(LanguageCode.ENGLISH)
            }

            loadAndCacheLanguage(languageCode)
            activeTranslations  = translationCache[languageCode] ?: emptyMap()
            _activeLanguage.value = languageCode

            logger.debug("Language loaded: ${languageCode.tag}, isRtl=$isRtl")
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

    /**
     * Returns the localised string for [key], falling back through:
     * 1. Active language translations
     * 2. English external fallback (if loaded)
     * 3. Embedded English fallback (bundled in the JAR)
     * 4. The raw [key] itself (so the UI always shows something)
     */
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
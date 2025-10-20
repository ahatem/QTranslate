package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalizationManager(
    private val appDataDirectory: File,
    private val parser: LanguageTomlParser,
    private val logger: Logger,
) {

    private val translationCache = mutableMapOf<LanguageCode, Map<String, String>>()
    private val languageMetaCache = mutableMapOf<LanguageCode, LocalizedLanguageMeta>()
    private val embeddedFallback: Map<String, String>
    private var activeTranslations: Map<String, String> = emptyMap()
    private var _activeLanguage: LanguageCode? = null

    private val externalFallback: Map<String, String>
        get() = translationCache[LanguageCode.ENGLISH] ?: emptyMap()


    val activeLanguage: LanguageCode?
        get() = _activeLanguage

    val languagesDirectory = File(appDataDirectory, "languages").also { it.mkdirs() }
    val availableLanguages: List<String> = languagesDirectory
        .listFiles { _, name -> name.endsWith(".toml") }
        ?.map { it.nameWithoutExtension }
        ?.sorted()
        ?: emptyList()

    init {
        embeddedFallback = loadEmbeddedFallback()
        _activeLanguage = LanguageCode.ENGLISH
    }

    private fun loadEmbeddedFallback(): Map<String, String> =
        runCatching {
            val stream = checkNotNull(
                this::class.java.classLoader.getResourceAsStream("localization/embedded_en.toml")
            ) { "Missing embedded localization file" }

            val text = stream.bufferedReader().readText()
            parser.parse(text).entries
        }.getOrElse {
            logger.error("Failed to load embedded fallback", it)
            emptyMap()
        }

    suspend fun loadLanguage(languageCode: LanguageCode) {
        withContext(Dispatchers.IO) {
            if (LanguageCode.ENGLISH !in translationCache) {
                loadAndCacheLanguage(LanguageCode.ENGLISH)
            }
            loadAndCacheLanguage(languageCode)
            activeTranslations = translationCache[languageCode] ?: emptyMap()
            _activeLanguage = languageCode
        }
    }

    private fun loadAndCacheLanguage(code: LanguageCode) {
        runCatching {
            val file = File(languagesDirectory, "$code.toml")
            if (!file.exists()) return

            val parsed = parser.parse(file.readText())
            translationCache[code] = parsed.entries
            parsed.meta?.let { languageMetaCache[code] = it }
        }.onFailure {
            logger.error("Failed to load language file: $code", it)
        }
    }

    fun getString(key: String, vararg args: Any): String {
        val value = activeTranslations[key]
            ?: externalFallback[key]
            ?: embeddedFallback[key]
            ?: key
        return if (args.isEmpty()) value else value.format(*args)
    }

    fun getLanguageMeta(language: LanguageCode): LocalizedLanguageMeta? =
        languageMetaCache[language]

    fun clearCache() {
        translationCache.clear()
        languageMetaCache.clear()
        activeTranslations = emptyMap()
    }
}
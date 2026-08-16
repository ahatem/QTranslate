package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LocalizationManager(
    private val appDataDirectory: File,
    private val parser: LanguageTomlParser,
    private val logger: Logger
) {
    // ConcurrentHashMap: written from Dispatchers.IO, read from EDT — no Mutex needed
    // for reads since we only ever replace whole values (no partial updates).
    private val translationCache  = ConcurrentHashMap<LanguageCode, Map<String, String>>()
    private val languageMetaCache = ConcurrentHashMap<LanguageCode, LocalizedLanguageMeta>()
    private val coverageCache     = ConcurrentHashMap<LanguageCode, TranslationCoverage>()
    private val embeddedFallback: Map<String, String>

    // @Volatile ensures EDT always sees the latest reference written by IO dispatcher.
    @Volatile private var activeTranslations: Map<String, String> = emptyMap()

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
            if (languageCode != LanguageCode.ENGLISH && !translationCache.containsKey(LanguageCode.ENGLISH)) {
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

    /**
     * How much of the interface a translation actually covers.
     *
     * Every missing key falls back to English, which is deliberate and keeps a half-finished
     * translation usable. It also makes the gaps invisible: a language can be a third English on
     * screen with nothing anywhere saying so, and the person who might fix it has no way to know
     * there is anything to fix.
     *
     * Measured against the embedded English file, which is the full set of strings the
     * application asks for.
     */
    suspend fun coverageOf(code: LanguageCode): TranslationCoverage =
        withContext(Dispatchers.IO) {
            coverageCache.getOrPut(code) {
                if (code == LanguageCode.ENGLISH) {
                    return@getOrPut TranslationCoverage(embeddedFallback.size, embeddedFallback.size)
                }
                val file = File(languagesDirectory, "${code.tag}.toml")
                if (!file.exists()) return@getOrPut TranslationCoverage(0, embeddedFallback.size)

                val translated = runCatching { parser.parse(file.readText()).entries }
                    .getOrDefault(emptyMap())

                // Counted against the English keys rather than the file's own, so a translation
                // still carrying keys the application has since dropped is not credited for them.
                TranslationCoverage(
                    translated = embeddedFallback.keys.count { it in translated },
                    total = embeddedFallback.size
                )
            }
        }

    /** The English keys a translation has no value for, in the order the application declares them. */
    suspend fun missingKeysOf(code: LanguageCode): List<String> =
        withContext(Dispatchers.IO) {
            val file = File(languagesDirectory, "${code.tag}.toml")
            val translated = if (file.exists()) {
                runCatching { parser.parse(file.readText()).entries }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            embeddedFallback.keys.filterNot { it in translated }
        }

    /** The English text for [key], which is what an untranslated string falls back to. */
    fun englishFor(key: String): String? = embeddedFallback[key]

    /** Every string the application asks for, in declaration order. */
    fun englishStrings(): Map<String, String> = embeddedFallback

    private fun loadAndCacheLanguage(code: LanguageCode) {
        if (translationCache.containsKey(code)) return

        // English ships inside the JAR as the fallback every other language is completed from, so
        // it has no file on disk and never needed one. Looking for it and warning made the app
        // report its own base language as missing on every start, and on every switch to English.
        if (code == LanguageCode.ENGLISH) {
            translationCache[code] = embeddedFallback
            return
        }

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
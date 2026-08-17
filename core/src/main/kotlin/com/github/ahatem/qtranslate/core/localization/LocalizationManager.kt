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

    /** Every string the application asks for, in declaration order. */
    fun englishStrings(): Map<String, String> = embeddedFallback

    /**
     * The English file verbatim, comments and all.
     *
     * The editor writes translations against this rather than against [englishStrings], because
     * the structure is the part worth copying: a file assembled from a map would be valid and
     * unreadable. See [LanguageFileWriter].
     */
    fun englishTemplate(): String = runCatching {
        checkNotNull(
            this::class.java.classLoader.getResourceAsStream(EMBEDDED_RESOURCE)
        ).bufferedReader().readText()
    }.getOrElse {
        logger.error("Failed to read the embedded English file", it)
        ""
    }

    /**
     * Drops everything cached for [code], so the next read comes from disk.
     *
     * Called after the editor writes a file. Without it the application would keep serving the
     * translation it loaded at startup, and someone editing a string would see nothing change.
     */
    fun forget(code: LanguageCode) {
        translationCache.remove(code)
        languageMetaCache.remove(code)
        coverageCache.remove(code)

        // Strings are served from activeTranslations, a snapshot taken when the language was
        // loaded, so clearing the caches alone changed nothing on screen. Someone editing the
        // language they were running saw none of their own work until they switched away and
        // back, while the hint beside the picker promised changes applied immediately.
        if (code == _activeLanguage.value) {
            loadAndCacheLanguage(code)
            activeTranslations = translationCache[code] ?: emptyMap()
        }
    }

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

    // -------------------------------------------------------------------------
    // Embedded fallback
    // -------------------------------------------------------------------------

    private fun loadEmbeddedFallback(): Map<String, String> =
        runCatching {
            val stream = checkNotNull(
                this::class.java.classLoader.getResourceAsStream(EMBEDDED_RESOURCE)
            ) { "Missing embedded localization file: $EMBEDDED_RESOURCE" }
            parser.parse(stream.bufferedReader().readText()).entries
        }.getOrElse {
            logger.error("Failed to load embedded fallback localization", it)
            emptyMap()
        }

    private companion object {
        const val EMBEDDED_RESOURCE = "localization/embedded_en.toml"
    }
}
package com.github.ahatem.qtranslate.api.language

/**
 * Represents a language tag standardized using the IETF BCP 47 specification.
 * Examples: `"en-US"` (English, US), `"zh-Hans"` (Simplified Chinese), `"auto"` (auto-detect).
 *
 * ### Provider codes vs. BCP-47
 * The constants in this companion object are standard BCP-47 tags and are the values
 * used in the public API (UI display, user preferences, service contracts).
 *
 * Individual translation/TTS providers may use non-standard internal codes (e.g. Google
 * uses `"zh-CN"` instead of `"zh-Hans"`, Bing uses `"jav"` for Javanese). These
 * provider-specific mappings are the responsibility of each plugin's `LanguageMapper`
 * and must never leak into this class or into [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages].
 *
 * @see <a href="https://en.wikipedia.org/wiki/IETF_language_tag">IETF Language Tag</a>
 */
@JvmInline
public value class LanguageCode(public val tag: String) {
    init {
        require(tag == "auto" || tag.matches(Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*\$"))) {
            "Language tag must be 'auto' or a well-formed BCP-47 tag, but was '$tag'."
        }
    }

    public companion object {
        // @formatter:off
        // --- Special & Auto-detection ---
        public val AUTO: LanguageCode                = LanguageCode("auto")

        // --- Top 50 Most Spoken Languages (Global Coverage) ---
        public val ENGLISH: LanguageCode             = LanguageCode("en")
        public val CHINESE_SIMPLIFIED: LanguageCode  = LanguageCode("zh-Hans")
        public val CHINESE_TRADITIONAL: LanguageCode = LanguageCode("zh-Hant")
        public val HINDI: LanguageCode               = LanguageCode("hi")
        public val SPANISH: LanguageCode             = LanguageCode("es")
        public val FRENCH: LanguageCode              = LanguageCode("fr")
        public val ARABIC: LanguageCode              = LanguageCode("ar")
        public val BENGALI: LanguageCode             = LanguageCode("bn")
        public val RUSSIAN: LanguageCode             = LanguageCode("ru")
        public val PORTUGUESE: LanguageCode          = LanguageCode("pt")
        public val INDONESIAN: LanguageCode          = LanguageCode("id")
        public val URDU: LanguageCode                = LanguageCode("ur")
        public val GERMAN: LanguageCode              = LanguageCode("de")
        public val JAPANESE: LanguageCode            = LanguageCode("ja")
        public val SWAHILI: LanguageCode             = LanguageCode("sw")
        public val MARATHI: LanguageCode             = LanguageCode("mr")
        public val TELUGU: LanguageCode              = LanguageCode("te")
        public val TURKISH: LanguageCode             = LanguageCode("tr")
        public val TAMIL: LanguageCode               = LanguageCode("ta")
        public val VIETNAMESE: LanguageCode          = LanguageCode("vi")
        public val KOREAN: LanguageCode              = LanguageCode("ko")
        public val ITALIAN: LanguageCode             = LanguageCode("it")
        public val THAI: LanguageCode                = LanguageCode("th")
        public val GUJARATI: LanguageCode            = LanguageCode("gu")
        public val JAVANESE: LanguageCode            = LanguageCode("jv")
        public val FARSI: LanguageCode               = LanguageCode("fa")
        public val HAUSA: LanguageCode               = LanguageCode("ha")
        public val BURMESE: LanguageCode             = LanguageCode("my")
        public val POLISH: LanguageCode              = LanguageCode("pl")
        public val UKRAINIAN: LanguageCode           = LanguageCode("uk")
        public val YORUBA: LanguageCode              = LanguageCode("yo")

        // --- Other Important Regional & Cultural Languages ---
        public val DUTCH: LanguageCode               = LanguageCode("nl")
        public val GREEK: LanguageCode               = LanguageCode("el")
        public val HEBREW: LanguageCode              = LanguageCode("he")
        public val HUNGARIAN: LanguageCode           = LanguageCode("hu")
        public val CZECH: LanguageCode               = LanguageCode("cs")
        public val SWEDISH: LanguageCode             = LanguageCode("sv")
        public val ROMANIAN: LanguageCode            = LanguageCode("ro")
        public val DANISH: LanguageCode              = LanguageCode("da")
        public val FINNISH: LanguageCode             = LanguageCode("fi")
        public val BULGARIAN: LanguageCode           = LanguageCode("bg")
        public val NORWEGIAN: LanguageCode           = LanguageCode("no")
        public val SLOVAK: LanguageCode              = LanguageCode("sk")
        public val SLOVENIAN: LanguageCode           = LanguageCode("sl")
        public val CATALAN: LanguageCode             = LanguageCode("ca")
        public val SERBIAN: LanguageCode             = LanguageCode("sr")
        public val CROATIAN: LanguageCode            = LanguageCode("hr")
        public val MALAY: LanguageCode               = LanguageCode("ms")
        public val NEPALI: LanguageCode              = LanguageCode("ne")
        public val SINHALA: LanguageCode             = LanguageCode("si")
        public val KHMER: LanguageCode               = LanguageCode("km")
        public val LAO: LanguageCode                 = LanguageCode("lo")
        public val AMHARIC: LanguageCode             = LanguageCode("am")
        public val SOMALI: LanguageCode              = LanguageCode("so")
        public val ZULU: LanguageCode                = LanguageCode("zu")
        public val AFRIKAANS: LanguageCode           = LanguageCode("af")
        public val ALBANIAN: LanguageCode            = LanguageCode("sq")
        public val ARMENIAN: LanguageCode            = LanguageCode("hy")
        public val AZERBAIJANI: LanguageCode         = LanguageCode("az")
        public val BASQUE: LanguageCode              = LanguageCode("eu")
        public val BELARUSIAN: LanguageCode          = LanguageCode("be")
        public val BOSNIAN: LanguageCode             = LanguageCode("bs")
        public val ESTONIAN: LanguageCode            = LanguageCode("et")
        public val GEORGIAN: LanguageCode            = LanguageCode("ka")
        public val ICELANDIC: LanguageCode           = LanguageCode("is")
        public val IRISH: LanguageCode               = LanguageCode("ga")
        public val LATVIAN: LanguageCode             = LanguageCode("lv")
        public val LITHUANIAN: LanguageCode          = LanguageCode("lt")
        public val MACEDONIAN: LanguageCode          = LanguageCode("mk")
        public val MALTESE: LanguageCode             = LanguageCode("mt")
        public val MONGOLIAN: LanguageCode           = LanguageCode("mn")
        public val WELSH: LanguageCode               = LanguageCode("cy")
        // @formatter:on
    }
}
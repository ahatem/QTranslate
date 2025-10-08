package com.github.ahatem.qtranslate.api.language

/**
 * Represents a language tag, standardized using the IETF BCP 47 specification.
 * Examples: "en-US" (English, US), "zh-Hans" (Simplified Chinese), "auto" (Auto-detect).
 *
 * @see <a href="https://en.wikipedia.org/wiki/IETF_language_tag">IETF Language Tag</a>
 */
@JvmInline
value class LanguageCode(val tag: String) {
    init {
        require(tag == "auto" || tag.matches(Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*\$"))) {
            "Language tag must be 'auto' or a well-formed BCP-47 tag, but was '$tag'."
        }
    }

    companion object {
        // @formatter:off
        // --- Special & Auto-detection ---
        val AUTO = LanguageCode("auto")

        // --- Top 50 Most Spoken Languages (Global Coverage) ---
        val ENGLISH             = LanguageCode("en")
        val CHINESE_SIMPLIFIED  = LanguageCode("zh-Hans")
        val CHINESE_TRADITIONAL = LanguageCode("zh-Hant")
        val HINDI               = LanguageCode("hi")
        val SPANISH             = LanguageCode("es")
        val FRENCH              = LanguageCode("fr")
        val ARABIC              = LanguageCode("ar")
        val BENGALI             = LanguageCode("bn")
        val RUSSIAN             = LanguageCode("ru")
        val PORTUGUESE          = LanguageCode("pt")
        val INDONESIAN          = LanguageCode("id")
        val URDU                = LanguageCode("ur")
        val GERMAN              = LanguageCode("de")
        val JAPANESE            = LanguageCode("ja")
        val SWAHILI             = LanguageCode("sw")
        val MARATHI             = LanguageCode("mr")
        val TELUGU              = LanguageCode("te")
        val TURKISH             = LanguageCode("tr")
        val TAMIL               = LanguageCode("ta")
        val VIETNAMESE          = LanguageCode("vi")
        val KOREAN              = LanguageCode("ko")
        val ITALIAN             = LanguageCode("it")
        val THAI                = LanguageCode("th")
        val GUJARATI            = LanguageCode("gu")
        val JAVANESE            = LanguageCode("jw")
        val FARSI               = LanguageCode("fa")
        val HAUSA               = LanguageCode("ha")
        val BURMESE             = LanguageCode("my")
        val POLISH              = LanguageCode("pl")
        val UKRAINIAN           = LanguageCode("uk")
        val YORUBA              = LanguageCode("yo")

        // --- Other Important Regional & Cultural Languages ---
        val DUTCH               = LanguageCode("nl")
        val GREEK               = LanguageCode("el")
        val HEBREW              = LanguageCode("he")
        val HUNGARIAN           = LanguageCode("hu")
        val CZECH               = LanguageCode("cs")
        val SWEDISH             = LanguageCode("sv")
        val ROMANIAN            = LanguageCode("ro")
        val DANISH              = LanguageCode("da")
        val FINNISH             = LanguageCode("fi")
        val BULGARIAN           = LanguageCode("bg")
        val NORWEGIAN           = LanguageCode("no")
        val SLOVAK              = LanguageCode("sk")
        val SLOVENIAN           = LanguageCode("sl")
        val CATALAN             = LanguageCode("ca")
        val SERBIAN             = LanguageCode("sr")
        val CROATIAN            = LanguageCode("hr")
        val MALAY               = LanguageCode("ms")
        val NEPALI              = LanguageCode("ne")
        val SINHALA             = LanguageCode("si")
        val KHMER               = LanguageCode("km")
        val LAO                 = LanguageCode("lo")
        val AMHARIC             = LanguageCode("am")
        val SOMALI              = LanguageCode("so")
        val ZULU                = LanguageCode("zu")
        val AFRIKAANS           = LanguageCode("af")
        val ALBANIAN            = LanguageCode("sq")
        val ARMENIAN            = LanguageCode("hy")
        val AZERBAIJANI         = LanguageCode("az")
        val BASQUE              = LanguageCode("eu")
        val BELARUSIAN          = LanguageCode("be")
        val BOSNIAN             = LanguageCode("bs")
        val ESTONIAN            = LanguageCode("et")
        val GEORGIAN            = LanguageCode("ka")
        val ICELANDIC           = LanguageCode("is")
        val IRISH               = LanguageCode("ga")
        val LATVIAN             = LanguageCode("lv")
        val LITHUANIAN          = LanguageCode("lt")
        val MACEDONIAN          = LanguageCode("mk")
        val MALTESE             = LanguageCode("mt")
        val MONGOLIAN           = LanguageCode("mn")
        val WELSH               = LanguageCode("cy")
        // @formatter:on
    }
}


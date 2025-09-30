package com.github.ahatem.qtranslate.services.translators.bing

import com.github.ahatem.qtranslate.models.Language
import com.github.ahatem.qtranslate.services.translators.abstraction.LanguageMapper
import com.github.ahatem.qtranslate.services.translators.common.LanguageDetector

class BingLanguageMapper(override val serviceName: String) : LanguageMapper() {

  override val supportedLanguages: Array<String>
    get() = arrayOf(
      "auto-detect",
      "af",
      "sq",
      "am",
      "ar",
      "hy",
      "as",
      "az",
      "bn",
      "bs",
      "bg",
      "my",
      "ca",
      "ca",
      "zh-Hans",
      "cs",
      "da",
      "nl",
      "nl",
      "en",
      "et",
      "fj",
      "fil",
      "fil",
      "fi",
      "fr",
      "fr-ca",
      "de",
      "ga",
      "el",
      "gu",
      "ht",
      "ht",
      "he",
      "hi",
      "hr",
      "hu",
      "is",
      "iu",
      "id",
      "it",
      "ja",
      "kn",
      "kk",
      "km",
      "ko",
      "ku",
      "lo",
      "lv",
      "lt",
      "ml",
      "mi",
      "mr",
      "ms",
      "mg",
      "mt",
      "ne",
      "nb",
      "nb",
      "or",
      "pa",
      "pa",
      "fa",
      "pl",
      "pt",
      "ps",
      "ps",
      "ro",
      "ro",
      "ro",
      "ru",
      "sk",
      "sl",
      "sm",
      "es",
      "es",
      "sr-Cyrl",
      "sw",
      "sv",
      "ty",
      "ta",
      "te",
      "th",
      "ti",
      "tlh-Latn",
      "tlh-Latn",
      "to",
      "tr",
      "uk",
      "ur",
      "vi",
      "cy",
      "zh-Hans",
      "zh-Hant",
      "yue",
      "prs",
      "mww",
      "tlh-Piqd",
      "kmr",
      "pt-pt",
      "otq",
      "sr-Cyrl",
      "sr-Latn",
      "yua"
    )

  override suspend fun detectLanguage(text: String): String {
    return doNormalize(Language(LanguageDetector.detectLanguage(text)))
  }

  override fun doNormalize(language: Language): String {
    return when (language.id) {
      "auto" -> "auto-detect"
      "zho" -> "zh-Hans"
      "och" -> "zh-Hant"
      else -> language.alpha2
    }
  }

  override fun doDenormalize(languageCode: String): Language {
    return when (languageCode) {
      "auto-detect" -> Language("auto")
      "zh-Hans" -> Language("zho")
      "zh-Hant" -> Language("och")
      else -> Language(languageCode)
    }
  }
}
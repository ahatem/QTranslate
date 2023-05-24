package com.pnix.qtranslate.translators.google

import com.pnix.qtranslate.models.Language
import com.pnix.qtranslate.translators.abstraction.LanguageMapper
import com.pnix.qtranslate.translators.common.LanguageDetector

class GoogleLanguageMapper(override val serviceName: String) : LanguageMapper() {

  override val supportedLanguages: Array<String>
    get() = arrayOf(
      "auto",
      "af",
      "sq",
      "am",
      "ar",
      "hy",
      "az",
      "eu",
      "be",
      "bn",
      "bs",
      "bg",
      "my",
      "ca",
      "ca",
      "ceb",
      "zh-cn",
      "co",
      "cs",
      "da",
      "nl",
      "nl",
      "en",
      "eo",
      "et",
      "fi",
      "fr",
      "fy",
      "ka",
      "de",
      "gd",
      "gd",
      "ga",
      "gl",
      "el",
      "gu",
      "ht",
      "ht",
      "ha",
      "haw",
      "he",
      "hi",
      "hr",
      "hu",
      "ig",
      "is",
      "id",
      "it",
      "jw",
      "ja",
      "kn",
      "kk",
      "km",
      "ky",
      "ky",
      "ko",
      "ku",
      "lo",
      "la",
      "lv",
      "lt",
      "lb",
      "lb",
      "mk",
      "ml",
      "mi",
      "mr",
      "ms",
      "mg",
      "mt",
      "mn",
      "ne",
      "no",
      "ny",
      "ny",
      "ny",
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
      "si",
      "si",
      "sk",
      "sl",
      "sm",
      "sn",
      "sd",
      "so",
      "st",
      "es",
      "es",
      "sr",
      "su",
      "sw",
      "sv",
      "ta",
      "te",
      "tg",
      "tl",
      "th",
      "tr",
      "ug",
      "ug",
      "uk",
      "ur",
      "uz",
      "vi",
      "cy",
      "xh",
      "yi",
      "yo",
      "zu",
      "zh-CN",
      "zh-TW"
    )

  override suspend fun detectLanguage(text: String): String {
    return  doNormalize(Language(LanguageDetector.detectLanguage(text)))
  }

  override fun doNormalize(language: Language): String {
    return when (language.id) {
      "zho" -> "zh-cn"
      "och" -> "zh-tw"
      else -> language.alpha2
    }
  }

  override fun doDenormalize(languageCode: String): Language {
    return when (languageCode) {
      "zh-cn" -> Language("zho")
      "zh-tw" -> Language("och")
      else -> Language(languageCode)
    }
  }

}
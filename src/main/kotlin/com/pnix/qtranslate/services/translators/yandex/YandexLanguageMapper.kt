package com.pnix.qtranslate.services.translators.yandex

import com.google.gson.Gson
import com.pnix.qtranslate.models.Language
import com.pnix.qtranslate.services.translators.abstraction.LanguageMapper
import kong.unirest.Unirest
import kotlinx.coroutines.future.await

data class YandexLanguageResponse(val code: Int, val lang: String)

class YandexLanguageMapper(
  override val serviceName: String,
  private val gson: Gson,
  private val generateSid: () -> String,
) : LanguageMapper() {

  override val supportedLanguages: Array<String>
    get() = arrayOf(
      "auto",
      "af",
      "sq",
      "am",
      "ar",
      "hy",
      "az",
      "ba",
      "eu",
      "be",
      "bn",
      "bs",
      "bg",
      "my",
      "ca",
      "ca",
      "ceb",
      "zh",
      "cv",
      "cs",
      "da",
      "nl",
      "nl",
      "en",
      "eo",
      "et",
      "fi",
      "fr",
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
      "he",
      "hi",
      "hr",
      "hu",
      "is",
      "id",
      "it",
      "jv",
      "ja",
      "kn",
      "kk",
      "km",
      "ky",
      "ky",
      "ko",
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
      "mrj",
      "mhr",
      "ne",
      "no",
      "pa",
      "pa",
      "pap",
      "fa",
      "pl",
      "pt",
      "ro",
      "ro",
      "ro",
      "ru",
      "sah",
      "si",
      "si",
      "sk",
      "sl",
      "es",
      "es",
      "sr",
      "sjn",
      "su",
      "sw",
      "sv",
      "ta",
      "tt",
      "te",
      "tg",
      "tl",
      "th",
      "tr",
      "udm",
      "uk",
      "ur",
      "uz",
      "vi",
      "cy",
      "xh",
      "yi",
      "zu",
      "kazlat",
      "uzbcyr",
      "emj"
    )


  override suspend fun detectLanguage(text: String): String {
    val url = "http://translate.yandex.net/api/v1/tr.json/detect"

    val params = mapOf("sid" to generateSid(), "srv" to "android", "text" to text, "hint" to "en")
    runCatching {
      Unirest.get(url).queryString(params).asStringAsync().await().body.let {
        val response = gson.fromJson(it, YandexLanguageResponse::class.java)
        return response.lang
      }
    }
    return "en"
  }

  override fun doNormalize(language: Language): String {
    return when (language.id) {
      "zho" -> "zh"
      "srd" -> "sjn"
      else -> language.alpha2
    }
  }

  override fun doDenormalize(languageCode: String): Language {
    return when (languageCode) {
      in arrayOf("zh", "zh-cn") -> Language("zho")
      "sjn" -> Language("srd")
      else -> Language(languageCode)
    }
  }

}
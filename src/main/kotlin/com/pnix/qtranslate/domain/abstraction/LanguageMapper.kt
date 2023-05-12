package com.pnix.qtranslate.domain.abstraction

import com.pnix.qtranslate.domain.models.Language
import kong.unirest.Unirest
import kotlinx.coroutines.future.await

class UnsupportedLanguageException(languageCode: String, serviceName: String) :
  Exception("The language $languageCode is not supported by $serviceName")

abstract class LanguageMapper {
  abstract val serviceName: String
  abstract val supportedLanguages: Array<String>

  fun isLanguageSupported(language: String): Boolean {
    val normalizedLanguage = doNormalize(Language(language))
    return supportedLanguages.contains(normalizedLanguage)
  }

  fun normalize(language: String): String {
    if (!isLanguageSupported(language)) throw UnsupportedLanguageException(language, serviceName)
    return doNormalize(Language(language))
  }

  fun denormalize(languageCode: String): Language {
    return doDenormalize(languageCode)
  }

  open suspend fun detectLanguage(text: String): String {
    val response = Unirest.post("https://www.translate.com/translator/ajax_lang_auto_detect")
      .header("accept", "*/*")
      .header("accept-language", "en-US,en;q=0.9")
      .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
      .header("sec-ch-ua", "\"Chromium\";v=\"112\", \"Microsoft Edge\";v=\"112\", \"Not:A-Brand\";v=\"99\"")
      .header("sec-ch-ua-mobile", "?0")
      .header("sec-ch-ua-platform", "\"Windows\"")
      .header("sec-fetch-dest", "empty")
      .header("sec-fetch-mode", "cors")
      .header("sec-fetch-site", "same-origin")
      .header("sec-gpc", "1")
      .header("x-requested-with", "XMLHttpRequest")
      .field("text_to_translate", text)
      .asJsonAsync().await().body.`object`
    return doNormalize(Language(response.getString("language")))
  }

  protected abstract fun doNormalize(language: Language): String
  protected abstract fun doDenormalize(languageCode: String): Language
}


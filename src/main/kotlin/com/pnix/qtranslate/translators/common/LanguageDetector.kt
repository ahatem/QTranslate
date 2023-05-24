package com.pnix.qtranslate.translators.common

import kong.unirest.Unirest
import kotlinx.coroutines.future.await

object LanguageDetector {
  suspend fun detectLanguage(text: String): String {
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
    return response.getString("language")
  }
}
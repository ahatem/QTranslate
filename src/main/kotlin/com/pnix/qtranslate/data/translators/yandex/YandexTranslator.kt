package com.pnix.qtranslate.data.translators.yandex

import com.google.gson.GsonBuilder
import com.pnix.qtranslate.domain.abstraction.LanguageMapper
import com.pnix.qtranslate.domain.abstraction.TranslatorService
import com.pnix.qtranslate.domain.models.SpellCheck
import com.pnix.qtranslate.domain.models.TextToSpeechResult
import com.pnix.qtranslate.domain.models.Translation
import kong.unirest.Unirest
import kotlinx.coroutines.future.await
import java.util.*

data class YandexLanguageRequest(val code: Long, val lang: String, val text: List<String>)

class YandexTranslator : TranslatorService() {
  override val serviceName: String get() = "Yandex"
  override val languageMapper: LanguageMapper get() = YandexLanguageMapper(serviceName, gson, this::generateSid)
  private val gson = GsonBuilder().setPrettyPrinting().create()

  private var sessionUCID = UUID.randomUUID().toString().replace("-", "")
  private var sessionRequestID = 0

  private fun generateSid(): String {
    val requestId = sessionRequestID
    sessionRequestID += 1
    return "$sessionUCID-$requestId-0"
  }

  override suspend fun doTranslate(text: String, targetLanguage: String, sourceLanguage: String): Translation {
    val url = "http://translate.yandex.net/api/v1/tr.json/translate"

    val newSourceLanguage =
      if (sourceLanguage == "auto") languageMapper.detectLanguage(text) else sourceLanguage

    val params = mapOf("sid" to generateSid(), "srv" to "android", "format" to "text")
    val data = mapOf("text" to text, "lang" to "${newSourceLanguage}-${targetLanguage}")

    runCatching {
      return Unirest.post(url).queryString(params).fields(data).asStringAsync().await().body.let {
        val response = gson.fromJson(it, YandexLanguageRequest::class.java)
        val detectedLanguage = response.lang.split("-").getOrElse(0) { sourceLanguage }
        val translatedText = response.text[0]
        Translation(detectedLanguage, translatedText)
      }
    }

    throw Exception("Something Wrong Happened!")
  }

  override suspend fun doTextToSpeech(text: String, sourceLanguage: String): TextToSpeechResult {
    TODO("Not yet implemented")
  }

  override suspend fun doSpellCheck(text: String, sourceLanguage: String): SpellCheck {
    TODO("Not yet implemented")
  }


}
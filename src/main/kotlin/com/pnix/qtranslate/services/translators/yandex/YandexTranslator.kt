package com.pnix.qtranslate.services.translators.yandex

import com.google.gson.GsonBuilder
import com.pnix.qtranslate.models.SpellCheck
import com.pnix.qtranslate.models.SpellCheckCorrection
import com.pnix.qtranslate.models.TextToSpeechResult
import com.pnix.qtranslate.models.Translation
import com.pnix.qtranslate.services.translators.abstraction.LanguageMapper
import com.pnix.qtranslate.services.translators.abstraction.TextToSpeechNotSupportedException
import com.pnix.qtranslate.services.translators.abstraction.TranslatorService
import kong.unirest.core.Unirest
import kotlinx.coroutines.future.await
import java.util.*

data class YandexLanguageRequest(val code: Long, val lang: String, val text: List<String>)

data class SpellCheckYandexResponse(
  val code: Int,
  val pos: Int,
  val row: Int,
  val col: Int,
  val len: Int,
  val word: String,
  val s: List<String>
)


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
    throw TextToSpeechNotSupportedException(serviceName)
  }

  override suspend fun doSpellCheck(text: String, sourceLanguage: String): SpellCheck {
    val url = "https://speller.yandex.net/services/spellservice.json/checkText"

    val newSourceLanguage =
      if (sourceLanguage == "auto") languageMapper.detectLanguage(text) else sourceLanguage

    val params = mapOf("sid" to generateSid(), "srv" to "android")
    val data = mapOf("text" to text, "lang" to newSourceLanguage, "options" to 8 + 4)

    runCatching {
      return Unirest.post(url).queryString(params).fields(data).asStringAsync().await().body.let {
        val response = gson.fromJson(it, Array<SpellCheckYandexResponse>::class.java).toList()
        val corrections = response.map { word ->
          SpellCheckCorrection(
            originalWord = word.word,
            suggestions = word.s,
            startIndex = word.pos,
            endIndex = word.pos + word.len
          )
        }
        val correctedText = buildString {
          var lastEndIndex = 0
          for (correction in corrections) {
            append(text.substring(lastEndIndex, correction.startIndex))
            if (correction.suggestions.isNotEmpty()) append(correction.suggestions[0])
            else append(correction.originalWord)
            lastEndIndex = correction.endIndex
          }
          append(text.substring(lastEndIndex))
        }
        SpellCheck(correctedText, corrections)
      }
    }

    throw Exception("Something Wrong Happened!")
  }


}
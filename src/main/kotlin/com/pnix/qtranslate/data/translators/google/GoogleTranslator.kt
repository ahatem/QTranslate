package com.pnix.qtranslate.data.translators.google

import com.google.gson.GsonBuilder
import com.pnix.qtranslate.data.common.SpellChecker
import com.pnix.qtranslate.data.common.UserAgent
import com.pnix.qtranslate.domain.abstraction.LanguageMapper
import com.pnix.qtranslate.domain.abstraction.TranslatorService
import com.pnix.qtranslate.domain.models.SpellCheck
import com.pnix.qtranslate.domain.models.TextToSpeechResult
import com.pnix.qtranslate.domain.models.Translation
import kong.unirest.Unirest
import kotlinx.coroutines.future.await
import java.util.*


private data class GoogleTranslateResponse(val sentences: List<Sentence>, val src: String, val confidence: Double)
private data class Sentence(val trans: String, val orig: String)

class GoogleTranslator : TranslatorService() {
  override val serviceName: String get() = "Google"
  override val languageMapper: LanguageMapper get() = GoogleLanguageMapper(serviceName)
  private val gson = GsonBuilder().setPrettyPrinting().create()

  override suspend fun doTranslate(text: String, targetLanguage: String, sourceLanguage: String): Translation {
    val headers = mapOf(
      "User-Agent" to UserAgent.random(),
      "accept" to "*/*",
      "Accept-Language" to "en-US,en-GB; q=0.5",
      "Accept-Encoding" to "gzip, deflate",
      "Connection" to "keep-alive"
    )

    val params1 = mapOf(
      "client" to "gtx",
      "dt" to "t",
      "dj" to 1,
      "sl" to sourceLanguage,
      "tl" to targetLanguage,
      "q" to text
    )

    runCatching {
      return Unirest.get("https://translate.googleapis.com/translate_a/single")
        .headers(headers).queryString(params1).asStringAsync().await().body.let {
          val response = gson.fromJson(it, GoogleTranslateResponse::class.java)
          val translatedText = response.sentences.joinToString { s -> s.trans }
          val detectedLanguage = response.src
          Translation(detectedLanguage, translatedText)
        }
    }

    val params2 = mapOf(
      "client" to "dict-chrome-ex",
      "dj" to 1,
      "sl" to sourceLanguage,
      "tl" to targetLanguage,
      "q" to text
    )
    runCatching {
      return Unirest.get("https://clients5.google.com/translate_a/t")
        .headers(headers).queryString(params2).asStringAsync().await().body.let {
          val response = gson.fromJson(it, Array<Array<String>>::class.java)[0]
          val translatedText = response[0]
          val detectedLanguage = response[1]
          Translation(detectedLanguage, translatedText)
        }
    }

    throw Exception("Something Wrong Happened!")
  }

  override suspend fun doTextToSpeech(text: String, sourceLanguage: String): TextToSpeechResult {
    val newSourceLanguage =
      if (sourceLanguage == "auto") languageMapper.detectLanguage(text) else sourceLanguage

    println(newSourceLanguage)

    val headers = mapOf(
      "User-Agent" to UserAgent.random(),
      "accept" to "*/*",
      "Accept-Language" to "en-US,en-GB; q=0.5",
      "Accept-Encoding" to "gzip, deflate",
      "Connection" to "keep-alive"
    )

    val params1 = mutableMapOf(
      "client" to "gtx",
      "ie" to "UTF-8",
      "tl" to newSourceLanguage,
      "q" to text
    )
    runCatching {
      return partitionString(text).map { part ->
        params1["q"] = part
        Unirest.get("https://translate.googleapis.com/translate_tts")
          .headers(headers).queryString(params1.toMap()).asBytesAsync().await().body
      }.reduce { acc, bytes -> acc + bytes }.run {
        TextToSpeechResult(this)
      }
    }

    val params2 = mutableMapOf(
      "client" to "tw-ob", "q" to text, "tl" to newSourceLanguage
    )
    runCatching {
      return partitionString(text).map { part ->
        params2["q"] = part
        Unirest.get("https://translate.googleapis.com/translate_tts")
          .headers(headers).queryString(params2.toMap()).asBytesAsync().await().body
      }.reduce { acc, bytes -> acc + bytes }.run {
        TextToSpeechResult(this)
      }
    }

    throw Exception("Something Wrong Happened!")

  }

  override suspend fun doSpellCheck(text: String, sourceLanguage: String): SpellCheck {
    return SpellChecker.spellCheck(text)
  }

  private fun partitionString(input: String): List<String> {
    return input.split("\\s+".toRegex())
      .fold(mutableListOf("")) { acc, str ->
        if ((acc.last() + " " + str).length > 200) {
          acc.add(str)
        } else {
          acc[acc.lastIndex] += " $str"
        }
        acc
      }.filter { it.isNotBlank() }
  }

}
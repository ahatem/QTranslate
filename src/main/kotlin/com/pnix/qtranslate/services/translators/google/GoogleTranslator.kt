package com.pnix.qtranslate.services.translators.google

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.pnix.qtranslate.common.UserAgent
import com.pnix.qtranslate.models.SpellCheck
import com.pnix.qtranslate.models.TextToSpeechResult
import com.pnix.qtranslate.models.Translation
import com.pnix.qtranslate.services.translators.abstraction.LanguageMapper
import com.pnix.qtranslate.services.translators.abstraction.TranslatorService
import com.pnix.qtranslate.services.translators.common.SpellChecker
import kong.unirest.Unirest
import kotlinx.coroutines.future.await
import java.util.*

private data class GoogleTranslateResponse(
  val sentences: List<Sentence>,
  val dict: List<Dict>?,
  val src: String,
  @SerializedName("alternative_translations")
  val alternativeTranslations: List<AlternativeTranslation>?,
  val confidence: Double,
  val spell: Spell,
  @SerializedName("ld_result")
  val ldResult: LdResult,
)

private data class Sentence(
  val trans: String?,
  val orig: String?,
  val backend: Long?,
  val translit: String?,
)

private data class Dict(
  val pos: String,
  val terms: List<String>,
  val entry: List<Entry>,
  @SerializedName("base_form")
  val baseForm: String,
  @SerializedName("pos_enum")
  val posEnum: Long,
)

private data class Entry(
  val word: String,
  @SerializedName("reverse_translation")
  val reverseTranslation: List<String>,
  val score: Double?,
)

private data class AlternativeTranslation(
  @SerializedName("src_phrase")
  val srcPhrase: String,
  val alternative: List<Alternative>,
  val srcunicodeoffsets: List<Srcunicodeoffset>,
  @SerializedName("raw_src_segment")
  val rawSrcSegment: String,
  @SerializedName("start_pos")
  val startPos: Long,
  @SerializedName("end_pos")
  val endPos: Long,
)

private data class Alternative(
  @SerializedName("word_postproc")
  val wordPostproc: String,
  val score: Long,
  @SerializedName("has_preceding_space")
  val hasPrecedingSpace: Boolean,
  @SerializedName("attach_to_next_token")
  val attachToNextToken: Boolean,
  val backends: List<Long>,
)

private data class Srcunicodeoffset(
  val begin: Long,
  val end: Long,
)

private data class Spell(
  @SerializedName("spell_html_res")
  val spellHtmlRes: String,
  @SerializedName("spell_res")
  val spellRes: String,
  @SerializedName("correction_type")
  val correctionType: List<Long>,
  val confident: Boolean,
)

private data class LdResult(
  val srclangs: List<String>,
  @SerializedName("srclangs_confidences")
  val srclangsConfidences: List<Double>,
  @SerializedName("extended_srclangs")
  val extendedSrclangs: List<String>,
)

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

    runCatching {
      return Unirest.get("https://translate.googleapis.com/translate_a/single")
        .headers(headers)
        .queryString("client", "gtx")
        .queryString("ie", "UTF-8")
        .queryString("oe", "UTF-8")
        .queryString("dj", 1)
        .queryString("dt", listOf("bd", "ex", "ld", "md", "rw", "rm", "ss", "t", "at", "qc"))
        .queryString("sl", sourceLanguage)
        .queryString("tl", targetLanguage)
        .queryString("q", text)
        .asStringAsync().await().body.let {
          val response = gson.fromJson(it, GoogleTranslateResponse::class.java)
          val translatedText = response.sentences.joinToString(separator = "") { s -> s.trans.orEmpty() }
          var moreInfo = "\n\n"
          for (dict in response.dict.orEmpty()) {
            moreInfo += "${dict.pos.replaceFirstChar(Char::titlecase)}:"
            for ((index, term) in dict.terms.withIndex()) {
              moreInfo += "\n\t$term (${dict.entry[index].reverseTranslation.joinToString()})"
            }
            moreInfo += "\n\n"
          }
          val detectedLanguage = response.src
          Translation(detectedLanguage, "$translatedText $moreInfo".trim())
        }
    }.onFailure {
      it.printStackTrace()
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
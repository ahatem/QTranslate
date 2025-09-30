package com.pnix.qtranslate.services.translators.common

import com.google.gson.Gson
import com.pnix.qtranslate.common.UserAgent
import com.pnix.qtranslate.models.SpellCheck
import com.pnix.qtranslate.models.SpellCheckCorrection
import kong.unirest.core.Unirest
import kotlinx.coroutines.future.await

private data class GrammarCheckerResponse(val matches: List<Match>)
private data class Match(
  val message: String,
  val shortMessage: String,
  val replacements: List<Replacement>,
  val offset: Long,
  val length: Long,
  val context: Context,
  val sentence: String,
  val type: Type,
  val rule: Rule,
  val ignoreForIncompleteSentence: Boolean,
  val contextForSureMatch: Long,
)

private data class Replacement(val value: String)
private data class Context(val text: String, val offset: Long, val length: Long)
private data class Type(val typeName: String)
private data class Rule(val id: String, val description: String, val issueType: String, val category: Category)
private data class Category(val id: String, val name: String)

object SpellChecker {
  suspend fun spellCheck(text: String, sourceLanguage: String = "en-US"): SpellCheck {
    val url = "https://grammarchecker.io/langtool"

    val headers = mapOf(
      "accept" to "*/*",
      "accept-language" to "en-US,en;q=0.9",
      "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
      "x-requested-with" to "XMLHttpRequest",
      "user-agent" to UserAgent.random()
    )


    val formData = mapOf(
      "disabledRules" to "WHITESPACE_RULE",
      "allowIncompleteResults" to "true",
      "text" to text,
      "language" to sourceLanguage
    )

    val response = Unirest.post(url)
      .headers(headers)
      .fields(formData)
      .asStringAsync().await().body

    val data = Gson().fromJson(response, GrammarCheckerResponse::class.java)

    val correctedText = StringBuilder(text)
    val corrections = mutableListOf<SpellCheckCorrection>()

    for (match in data.matches) {
      val replacement = match.replacements.firstOrNull()?.value ?: continue
      correctedText.replace(match.offset.toInt(), (match.offset + match.length).toInt(), replacement)
      corrections.add(
        SpellCheckCorrection(
          originalWord = text.substring(match.offset.toInt(), (match.offset + match.length).toInt()),
          suggestions = match.replacements.map { it.value },
          startIndex = match.offset.toInt(),
          endIndex = (match.offset + match.length).toInt()
        )
      )
    }

    return SpellCheck(correctedText.toString(), corrections.toList())
  }
}
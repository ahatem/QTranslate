package com.github.ahatem.qtranslate.services.translators.reverso

import com.github.ahatem.qtranslate.common.UserAgent
import com.github.ahatem.qtranslate.models.*
import com.github.ahatem.qtranslate.services.translators.abstraction.LanguageMapper
import com.github.ahatem.qtranslate.services.translators.abstraction.TranslatorService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kong.unirest.core.Unirest
import kotlinx.coroutines.future.await
import java.util.*

private data class ReversoRequest(
    val format: String = "text",
    val from: String,
    val to: String,
    val input: String,
    val options: Options = Options()
)

private data class Options(
    val sentenceSplitter: Boolean = true,
    val origin: String = "translation.web",
    val contextResults: Boolean = true,
    val languageDetection: Boolean = false
)

private data class ReversoTranslateResponse(
    val from: String,
    val to: String,
    val input: List<String>,
    val translation: List<String>,
)

private data class TextCorrection(
    val language: String,
    val text: String,
    val truncated: Boolean,
    val corrections: List<Correction>,
    val sentences: List<Sentence>,
    val stats: Stats,
)

private data class Correction(
    val group: String,
    val type: String,
    val shortDescription: String,
    val longDescription: String,
    val startIndex: Long,
    val endIndex: Long,
    val mistakeText: String,
    val correctionText: String?,
    val correctionDefinition: String?,
    val suggestions: List<Suggestion>,
    val mistakeDefinition: String?,
)

private data class Suggestion(
    val text: String,
    val definition: String?,
    val category: String,
)

private data class Sentence(
    val startIndex: Long,
    val endIndex: Long,
    val status: String,
)

private data class Stats(
    val textLength: Long,
    val wordCount: Long,
    val sentenceCount: Long,
    val longestSentence: Long,
)

private data class AvailableVoicesResponse(
    @SerializedName("Voices")
    val voices: List<Voice>,
)

private data class Voice(
    @SerializedName("Name")
    val name: String,
    @SerializedName("Language")
    val language: String,
    @SerializedName("LangCode")
    val langCode: String,
    @SerializedName("Type")
    val type: String,
    @SerializedName("Gender")
    val gender: String,
)

class ReversoTranslator : TranslatorService() {
    override val serviceName: String get() = "Reverso"
    override val languageMapper: LanguageMapper get() = ReversoLanguageMapper(serviceName, gson)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    override suspend fun doTranslate(text: String, targetLanguage: String, sourceLanguage: String): Translation {
        val newSourceLanguage =
            if (sourceLanguage == "auto") languageMapper.detectLanguage(text) else sourceLanguage

        val url = "https://api.reverso.net/translate/v1/translation"

        val headers = mapOf(
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "en-US,en;q=0.9",
            "content-type" to "application/json",
            "origin" to "https://www.reverso.net",
            "priority" to "u=1, i",
            "referer" to "https://www.reverso.net/",
            "sec-ch-ua" to "\"Chromium\";v=\"134\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"134\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-site",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "x-reverso-origin" to "translation.web"
        )

        val requestBody = ReversoRequest(
            from = newSourceLanguage,
            to = targetLanguage,
            input = text,
        )

        runCatching {
            return Unirest.post(url).headers(headers).body(gson.toJson(requestBody))
                .asStringAsync().await().body.let {
                    println("Reverso Response: $it")
                    val response = gson.fromJson(it, ReversoTranslateResponse::class.java)
                    val detectedLanguage = response.from
                    val translatedText = response.translation[0]
                    Translation(detectedLanguage, translatedText)
                }
        }.onFailure {
            it.printStackTrace()
        }

        throw Exception("Something Wrong Happened!")
    }

    override suspend fun doTextToSpeech(text: String, sourceLanguage: String): TextToSpeechResult {
        val newSourceLanguage =
            if (sourceLanguage == "auto") (languageMapper as ReversoLanguageMapper).detectLanguage(text) else sourceLanguage

        val headers = mapOf(
            "user-agent" to UserAgent.random(),
            "content-type" to "application/json"
        )

        val supportedLanguagesUrl = "https://voice.reverso.net/RestPronunciation.svc/v1/output=json/GetAvailableVoices"
        val supportedLanguagesResult = Unirest.get(supportedLanguagesUrl).headers(headers).asStringAsync().await().body
        val supportedLanguagesList = gson.fromJson(supportedLanguagesResult, AvailableVoicesResponse::class.java)

        val lang = Language(newSourceLanguage)
        val voice = supportedLanguagesList.voices.firstOrNull { it.language.lowercase() in lang.name.lowercase() }
            ?: supportedLanguagesList.voices.first { "english" in it.language.lowercase() }

        runCatching {
            val url =
                "https://voice.reverso.net/RestPronunciation.svc/v1/output=json/GetVoiceStream/voiceName=${voice.name}?voiceSpeed=${1}&inputText=${
                    Base64.getEncoder().encodeToString(text.toByteArray())
                }"
            val response = Unirest.get(url).headers(headers).asBytes().body
            return TextToSpeechResult(response)
        }

        throw Exception("Something Wrong Happened!")
    }

    override suspend fun doSpellCheck(text: String, sourceLanguage: String): SpellCheck {
        val newSourceLanguage =
            if (sourceLanguage == "auto") (languageMapper as ReversoLanguageMapper).detectLanguage(text) else sourceLanguage

        val requestBody = """
    {
        "englishDialect": "indifferent",
        "autoReplace": true,
        "getCorrectionDetails": true,
        "interfaceLanguage": "en",
        "locale": "",
        "language": "$newSourceLanguage",
        "text": "$text",
        "originalText": "",
        "spellingFeedbackOptions": {
            "insertFeedback": true,
            "userLoggedOn": false
        },
        "origin": "interactive",
        "isHtml": false,
        "IsUserPremium": false
    }
  """.trimIndent()

        runCatching {
            Unirest.post("https://orthographe.reverso.net/api/v1/Spelling/")
                .header("accept", "text/json")
                .header("user-agent", UserAgent.random())
                .header("accept-language", "en-US,en;q=0.9")
                .header("content-type", "application/*+json")
                .header("referrer", "https://www.reverso.net/")
                .header("referrerPolicy", "strict-origin-when-cross-origin")
                .body(requestBody)
                .asStringAsync().await().body.apply {
                    val response = Gson().fromJson(this, TextCorrection::class.java)
                    val correctedText = response.text
                    val corrections = mutableListOf<SpellCheckCorrection>()
                    response.corrections.forEach { correction ->
                        val startIndex = correction.startIndex.toInt()
                        val endIndex = correction.endIndex.toInt()
                        corrections.add(
                            SpellCheckCorrection(
                                originalWord = correction.mistakeText,
                                suggestions = correction.suggestions.map { it.text },
                                startIndex = startIndex,
                                endIndex = endIndex
                            )
                        )
                    }
                    return SpellCheck(correctedText, corrections)
                }
        }.onFailure {
            it.printStackTrace()
        }

        throw Exception("Something Wrong Happened!")
    }

}
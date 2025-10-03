package com.github.ahatem.qtranslate.services.translators.bing

import com.github.ahatem.qtranslate.models.SpellCheck
import com.github.ahatem.qtranslate.models.TextToSpeechResult
import com.github.ahatem.qtranslate.models.Translation
import com.github.ahatem.qtranslate.services.translators.abstraction.LanguageMapper
import com.github.ahatem.qtranslate.services.translators.abstraction.TextToSpeechNotSupportedException
import com.github.ahatem.qtranslate.services.translators.abstraction.TranslatorService
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kong.unirest.core.Unirest
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.hours

private data class BingAuth(val ig: String, val iid: String, val key: String, val token: String)

private data class BingTranslateResponse(val detectedLanguage: DetectedLanguage, val translations: List<BTranslation>)
private data class DetectedLanguage(val language: String, val score: Double)
private data class BTranslation(val text: String, val to: String)

class BingTranslator : TranslatorService() {
    override val serviceName: String get() = "Bing"
    override val languageMapper: LanguageMapper get() = BingLanguageMapper(serviceName)

    private var startTime = System.currentTimeMillis()

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private var auth: BingAuth? = null
        get() {
            if (!isOneHourElapsed() && field != null) return field
            startTime = System.currentTimeMillis()
            field = runBlocking { getKeyAndToken() }
            return field
        }

    override suspend fun doTranslate(text: String, targetLanguage: String, sourceLanguage: String): Translation {
        val url = "https://www.bing.com/ttranslatev3"

        val requestBody = mapOf(
            "text" to text,
            "fromLang" to sourceLanguage,
            "to" to targetLanguage,
            "token" to auth!!.token,
            "key" to auth!!.key,
            "isAuthv2" to true
        )
        runCatching {
            val request = post(url).fields(requestBody).asStringAsync().await()
            return request.body.let {
                println(it)
                val response = gson.fromJson(it, Array<BingTranslateResponse>::class.java)[0]
                val detectedLanguage = response.detectedLanguage.language
                val translatedText = response.translations.joinToString { s -> s.text }
                Translation(detectedLanguage, translatedText)
            }
        }.onFailure { it.printStackTrace() }

        throw Exception("Something Wrong Happened!")
    }

    override suspend fun doTextToSpeech(text: String, sourceLanguage: String): TextToSpeechResult {
        throw TextToSpeechNotSupportedException(serviceName)
    }

    override suspend fun doSpellCheck(text: String, sourceLanguage: String): SpellCheck {
        val url = "https://www.bing.com/tspellcheckv3"
        return partitionString(text).map {
            val requestBody = mapOf(
                "text" to it,
                "fromLang" to sourceLanguage,
                "token" to auth!!.token,
                "key" to auth!!.key,
                "isAuthv2" to true
            )
            it to post(url).fields(requestBody).asStringAsync().await().body
        }.joinToString { (part, json) ->
            val response = JsonParser.parseString(json).asJsonObject
            val correctedText = response.get("correctedText").asString.ifEmpty { part }
            correctedText
        }.run {
            SpellCheck(trim())
        }
    }

    private fun partitionString(input: String): List<String> {
        return input.split("\\s+".toRegex())
            .fold(mutableListOf("")) { acc, str ->
                if ((acc.last() + " " + str).length > 46) {
                    acc.add(str)
                } else {
                    acc[acc.lastIndex] += " $str"
                }
                acc
            }.filter { it.isNotBlank() }
    }

    private fun post(url: String) = Unirest.post(url).apply {
        val params = mapOf(
            "isVertical" to 1,
            "IG" to auth!!.ig,
            "IID" to auth!!.iid,
        )
        val headers = mapOf(
            "accept" to "*/*",
            "accept-language" to "en-US,en;q=0.9",
            "content-type" to "application/x-www-form-urlencoded",
        )
        queryString(params).headers(headers)
    }

    private suspend fun getKeyAndToken(): BingAuth {
        println("getting token")
        val response = Unirest.get("https://www.bing.com/translator").asStringAsync().await().body
        val parsedIG = Regex("""IG:"(.*?)"""").findAll(response).map { it.groupValues[1] }.toList()
        val parsedIID = Regex("""data-iid="(.*?)"""").findAll(response).map { it.groupValues[1] }.toList()
        val parsedHelperInfo =
            Regex("""params_AbusePreventionHelper = (.*?);""").findAll(response).map { it.groupValues[1] }.toList()
        val normalizedHelperInfo: List<String> =
            gson.fromJson(parsedHelperInfo[0], object : TypeToken<List<String>>() {}.type)
        val ig = parsedIG.first()
        val iid = parsedIID.first()
        val key = normalizedHelperInfo[0]
        val token = normalizedHelperInfo[1]
        return BingAuth(ig, iid, key, token)
    }

    private fun isOneHourElapsed(): Boolean {
        val currentTimeMillis = System.currentTimeMillis()
        return (currentTimeMillis - startTime) >= 1.hours.inWholeMilliseconds
    }

}
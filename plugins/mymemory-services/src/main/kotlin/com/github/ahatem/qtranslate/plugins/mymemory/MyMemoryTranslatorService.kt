package com.github.ahatem.qtranslate.plugins.mymemory


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.HttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.parser.Parser

internal class MyMemoryTranslatorService(
    private val context: PluginContext,
    private val httpClient: HttpClient,
    private val apiConfig: ApiConfig,
    private val minimumRequestIntervalMillis: Long = 350
) : Translator {
    override val key = "mymemory-services-translate"
    override val name = "MyMemory (Free)"
    override val iconPath = "assets/mymemory.svg"
    override val version = "1.0.0"
    override val supportedLanguages = SupportedLanguages.Specific(SUPPORTED_LANGUAGES)

    private val parser = createJsonParser<MyMemoryResponse>(context)
    private val requestMutex = Mutex()
    private var lastRequestAtNanos = 0L

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        if (request.sourceLanguage == LanguageCode.AUTO) {
            return Err(ServiceError.UnsupportedLanguageError(
                LanguageCode.AUTO,
                "MyMemory requires a source language; automatic detection is not available."
            ))
        }

        return coroutineBinding {
            val translated = StringBuilder()
            for (segment in Utf8Segmenter.split(request.text, MAX_QUERY_BYTES)) {
                translated.append(translateSegment(
                    segment,
                    request.sourceLanguage,
                    request.targetLanguage
                ).bind())
            }
            TranslationResponse(translated.toString())
        }
    }

    private suspend fun translateSegment(
        text: String,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode
    ): Result<String, ServiceError> = requestMutex.withLock {
        coroutineBinding {
            paceRequest()
            val responseBody = httpClient.get(
                url = ENDPOINT,
                headers = apiConfig.createJsonHeaders(),
                queryParams = mapOf(
                    "q" to text,
                    "langpair" to "${providerCode(sourceLanguage)}|${providerCode(targetLanguage)}",
                    "mt" to 1
                )
            ).bind()
            lastRequestAtNanos = System.nanoTime()

            val parsed = parser.parse(responseBody).bind()
            mapResponse(parsed).bind()
        }
    }

    private fun mapResponse(response: MyMemoryResponse): Result<String, ServiceError> {
        val translatedText = response.responseData?.translatedText.orEmpty()
        if (response.responseStatus in 200..299 && translatedText.isNotBlank()) {
            return Ok(Parser.unescapeEntities(translatedText, false))
        }

        val details = response.responseDetails.ifBlank { "The provider did not return a translation." }
        return when {
            response.quotaFinished || response.responseStatus == 429 -> Err(ServiceError.RateLimitError(
                "MyMemory's free request limit has been reached. Try again later or choose another service."
            ))
            response.responseStatus >= 500 -> Err(ServiceError.ServiceUnavailableError(
                "MyMemory's free endpoint is temporarily unavailable. Try again later."
            ))
            else -> Err(ServiceError.InvalidResponseError("MyMemory could not translate this text: $details"))
        }
    }

    private suspend fun paceRequest() {
        if (lastRequestAtNanos == 0L || minimumRequestIntervalMillis <= 0) return
        val elapsedMillis = (System.nanoTime() - lastRequestAtNanos) / 1_000_000
        val remainingMillis = minimumRequestIntervalMillis - elapsedMillis
        if (remainingMillis > 0) delay(remainingMillis)
    }

    private fun providerCode(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED -> "zh-CN"
        LanguageCode.CHINESE_TRADITIONAL -> "zh-TW"
        else -> language.tag
    }

    companion object {
        private const val ENDPOINT = "https://api.mymemory.translated.net/get"
        private const val MAX_QUERY_BYTES = 500

        private val SUPPORTED_LANGUAGES = setOf(
            LanguageCode.ENGLISH, LanguageCode.ARABIC, LanguageCode.SPANISH, LanguageCode.FRENCH,
            LanguageCode.GERMAN, LanguageCode.ITALIAN, LanguageCode.PORTUGUESE, LanguageCode.RUSSIAN,
            LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.CHINESE_TRADITIONAL, LanguageCode.JAPANESE,
            LanguageCode.KOREAN, LanguageCode.HINDI, LanguageCode.BENGALI, LanguageCode.INDONESIAN,
            LanguageCode.URDU, LanguageCode.TURKISH, LanguageCode.VIETNAMESE, LanguageCode.DUTCH,
            LanguageCode.GREEK, LanguageCode.HEBREW, LanguageCode.POLISH, LanguageCode.UKRAINIAN,
            LanguageCode.CZECH, LanguageCode.SWEDISH, LanguageCode.ROMANIAN, LanguageCode.DANISH,
            LanguageCode.FINNISH, LanguageCode.BULGARIAN, LanguageCode.NORWEGIAN, LanguageCode.SLOVAK,
            LanguageCode.SLOVENIAN, LanguageCode.CATALAN, LanguageCode.SERBIAN, LanguageCode.CROATIAN,
            LanguageCode.MALAY, LanguageCode.THAI, LanguageCode.FARSI, LanguageCode.AFRIKAANS,
            LanguageCode.ALBANIAN, LanguageCode.ARMENIAN, LanguageCode.AZERBAIJANI, LanguageCode.BASQUE,
            LanguageCode.BELARUSIAN, LanguageCode.BOSNIAN, LanguageCode.ESTONIAN, LanguageCode.GEORGIAN,
            LanguageCode.HUNGARIAN, LanguageCode.ICELANDIC, LanguageCode.IRISH, LanguageCode.LATVIAN,
            LanguageCode.LITHUANIAN, LanguageCode.MACEDONIAN
        )
    }
}

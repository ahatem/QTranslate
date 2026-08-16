package com.github.ahatem.qtranslate.plugins.yandexweb


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

internal class YandexWebTranslatorService(private val client: YandexWebClient) : Translator {
    override val key = "yandex-web-translator"
    override val name = "Yandex Web (Unofficial)"
    override val version = "1.0.0"
    override val iconPath = "assets/yandex.svg"
    override val supportedLanguages = SupportedLanguages.Specific(YandexWebLanguageMapper.supportedLanguages)

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        if (request.text.length > MAX_TEXT_LENGTH) {
            return Err(ServiceError.InvalidInputError("Yandex Web accepts up to $MAX_TEXT_LENGTH characters per request."))
        }
        if (request.sourceLanguage !in YandexWebLanguageMapper.supportedLanguages) {
            return unsupported(request.sourceLanguage)
        }
        if (request.targetLanguage == LanguageCode.AUTO ||
            request.targetLanguage !in YandexWebLanguageMapper.supportedLanguages
        ) {
            return unsupported(request.targetLanguage)
        }

        return client.translate(
            request.text,
            YandexWebLanguageMapper.toProviderCode(request.targetLanguage)
        ).map { response ->
            TranslationResponse(
                translatedText = response.text,
                detectedLanguage = response.from.takeIf { request.sourceLanguage == LanguageCode.AUTO }
                    ?.let(YandexWebLanguageMapper::fromProviderCode)
            )
        }
    }

    private fun unsupported(language: LanguageCode) = Err(
        ServiceError.UnsupportedLanguageError(
            language,
            "Yandex Web does not support language '${language.tag}'."
        )
    )

    private companion object {
        const val MAX_TEXT_LENGTH = 5_000
    }
}

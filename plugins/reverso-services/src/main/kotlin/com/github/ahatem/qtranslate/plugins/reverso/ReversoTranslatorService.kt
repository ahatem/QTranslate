package com.github.ahatem.qtranslate.plugins.reverso

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr

internal class ReversoTranslatorService(
    private val client: ReversoClient
) : Translator {
    override val id = "reverso-services-translation"
    override val name = "Reverso Translation"
    override val version = "1.0.0"
    override val iconPath = "assets/reverso.png"
    override val supportedLanguages = ReversoLanguageMapper.supportedLanguages

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        validateLanguage(request.sourceLanguage)?.let { return Err(it) }
        validateLanguage(request.targetLanguage)?.let { return Err(it) }

        return coroutineBinding {
            val response = client.translate(ReversoTextRequest(
                source = request.text,
                direction = "${ReversoLanguageMapper.code(request.sourceLanguage)}-${ReversoLanguageMapper.code(request.targetLanguage)}"
            )).bind()
            if (!response.success || response.error) Err(client.providerError(response.message)).bind<Unit>()

            val translation = response.translation.takeIf(String::isNotBlank)
                .toResultOr { ServiceError.InvalidResponseError(
                    "Reverso Translation returned no text. The free endpoint may have changed."
                ) }.bind()
            TranslationResponse(translatedText = translation)
        }
    }

    private fun validateLanguage(language: LanguageCode): ServiceError? =
        if (language !in ReversoLanguageMapper.languages) {
            ServiceError.UnsupportedLanguageError(
                language,
                if (language == LanguageCode.AUTO) {
                    "Reverso Translation does not support automatic language detection."
                } else {
                    "Reverso Translation does not support ${language.tag}."
                }
            )
        } else null
}

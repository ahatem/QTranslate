package com.pnix.qtranslate.services.translators.abstraction

import com.pnix.qtranslate.common.Localizer
import com.pnix.qtranslate.models.Language

class UnsupportedLanguageException(languageCode: String, serviceName: String) :
  Exception(
    Localizer.localize("status_panel_error_text_not_supported_language")
      .format(Language(languageCode).name, serviceName)
  )

class TextToSpeechNotSupportedException(serviceName: String) :
  Exception(
    Localizer.localize("status_panel_error_text_text_to_speech_not_supported")
      .format(serviceName)
  )

abstract class LanguageMapper {
  abstract val serviceName: String
  abstract val supportedLanguages: Array<String>

  private fun isLanguageSupported(language: String): Boolean {
    val normalizedLanguage = doNormalize(Language(language))
    return supportedLanguages.contains(normalizedLanguage)
  }

  fun normalize(language: String): String {
    if (!isLanguageSupported(language)) throw UnsupportedLanguageException(language, serviceName)
    return doNormalize(Language(language))
  }

  fun denormalize(languageCode: String): Language {
    return doDenormalize(languageCode)
  }

  abstract suspend fun detectLanguage(text: String): String

  protected abstract fun doNormalize(language: Language): String
  protected abstract fun doDenormalize(languageCode: String): Language
}


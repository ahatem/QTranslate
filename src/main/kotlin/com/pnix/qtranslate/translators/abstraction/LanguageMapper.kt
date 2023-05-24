package com.pnix.qtranslate.translators.abstraction

import com.pnix.qtranslate.models.Language

class UnsupportedLanguageException(languageCode: String, serviceName: String) :
  Exception("The language $languageCode is not supported by $serviceName")

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


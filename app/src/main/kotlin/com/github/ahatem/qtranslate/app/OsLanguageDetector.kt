package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.Locale

object OsLanguageDetector {
    fun detect(availableLanguages: List<String>): LanguageCode {
        val osLocale = Locale.getDefault()

        val fullTag = "${osLocale.language}-${osLocale.country}"
        if (fullTag in availableLanguages) return LanguageCode(fullTag)

        val shortTag = osLocale.language
        if (shortTag in availableLanguages) return LanguageCode(shortTag)

        val familyMatch = availableLanguages.firstOrNull {
            it.startsWith("$shortTag-")
        }
        if (familyMatch != null) return LanguageCode(familyMatch)

        return LanguageCode.ENGLISH
    }
}
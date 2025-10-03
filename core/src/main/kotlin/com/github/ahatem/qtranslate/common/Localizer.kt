package com.github.ahatem.qtranslate.common

import com.github.ahatem.qtranslate.models.Configurations
import com.github.ahatem.qtranslate.models.Language
import java.util.*

object Localizer {
    private val backup = ResourceBundle.getBundle("app_translations/translations", Locale("en"))

    var currentLocale = Locale(Configurations.interfaceLanguage)
        private set
    private val configurationLocale get() = Locale(Configurations.interfaceLanguage)

    val isLocaleChanged get() = currentLocale != configurationLocale

    val supportedLanguages = arrayOf(
        Language("ar"),
        Language("en"),
    )

    private var translations =
        ResourceBundle.getBundle("app_translations/translations", currentLocale)

    fun localize(key: String): String {
        return runCatching { translations.getString(key) }.getOrDefault(backup.getString(key))
    }

    fun localizeDir(dir: String): String {
        return when (dir) {
            "left" -> if (currentLocale.language == "ar") "right" else "left"
            "right" -> if (currentLocale.language == "ar") "left" else "right"
            else -> dir
        }
    }

    fun updateCurrentLocale() {
        currentLocale = configurationLocale
        translations = ResourceBundle.getBundle("app_translations/translations", currentLocale)
    }

}
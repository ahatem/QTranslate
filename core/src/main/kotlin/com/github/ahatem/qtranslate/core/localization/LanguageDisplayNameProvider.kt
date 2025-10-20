package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.*

fun LanguageCode.getDisplayName(uiLocale: Locale = Locale.getDefault()): String {
    if (this == LanguageCode.AUTO) return "Auto-Detect"

    return runCatching {
        val locale = Locale.forLanguageTag(tag)

        val languageName = locale.getDisplayLanguage(uiLocale)
            .replaceFirstChar { it.uppercase() }

        val variants = buildList {
            locale.getDisplayCountry(uiLocale).takeIf { it.isNotEmpty() }?.let { add(it) }
            locale.getDisplayScript(uiLocale)
                .removeSuffix("Han")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { add(it) }
        }

        if (variants.isEmpty()) languageName
        else "$languageName (${variants.joinToString(", ")})"
    }.getOrElse {
        tag.uppercase()
    }
}

fun LanguageCode.toLocal(): Locale = Locale.forLanguageTag(tag)
fun Locale.toLanguageCode(): LanguageCode = LanguageCode(tag = language)
package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.Locale

internal object VoiceLocaleMapper {
    fun codes(locale: String): Set<LanguageCode> {
        val tag = locale.replace('_', '-')
        val parsed = Locale.forLanguageTag(tag)
        val language = parsed.language.takeIf { it.length in 2..8 && it != "und" } ?: return emptySet()
        return buildSet {
            runCatching { LanguageCode(parsed.toLanguageTag()) }.getOrNull()?.let(::add)
            add(LanguageCode(language))
            if (language == "zh") {
                when (parsed.country.uppercase()) {
                    "CN", "SG" -> add(LanguageCode.CHINESE_SIMPLIFIED)
                    "TW", "HK", "MO" -> add(LanguageCode.CHINESE_TRADITIONAL)
                }
            }
        }
    }

    fun preferred(requested: LanguageCode, voices: List<SystemVoice>): SystemVoice? {
        if (requested == LanguageCode.AUTO) return null
        return voices.firstOrNull { requested.tag.equals(it.locale.replace('_', '-'), ignoreCase = true) }
            ?: voices.firstOrNull { requested in codes(it.locale) }
    }
}

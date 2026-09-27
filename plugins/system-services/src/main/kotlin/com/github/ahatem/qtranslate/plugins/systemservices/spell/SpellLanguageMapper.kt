package com.github.ahatem.qtranslate.plugins.systemservices.spell

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.Locale

internal class SpellLanguageMapper(nativeTags: Set<String>) {
    private val native = nativeTags.mapNotNull { raw ->
        val parsed = Locale.forLanguageTag(raw.replace('_', '-'))
        val canonical = parsed.toLanguageTag()
        if (parsed.language.isBlank() || canonical == "und") null
        else runCatching { LanguageCode(canonical) }.getOrNull()?.let { it to raw }
    }.sortedBy { it.first.tag }.toMap()

    val supported: Set<LanguageCode> = buildSet {
        addAll(native.keys)
        native.keys.map { LanguageCode(it.tag.substringBefore('-')) }.forEach(::add)
    }

    fun nativeTag(requested: LanguageCode): String? {
        if (requested == LanguageCode.AUTO) return null
        native.entries.firstOrNull { it.key.tag.equals(requested.tag, ignoreCase = true) }?.let { return it.value }
        if ('-' in requested.tag) return null
        return native.entries.firstOrNull { it.key.tag.substringBefore('-').equals(requested.tag, ignoreCase = true) }?.value
    }
}

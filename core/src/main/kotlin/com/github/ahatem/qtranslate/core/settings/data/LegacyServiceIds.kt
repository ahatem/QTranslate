package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId

/**
 * Translates service identifiers written by versions before the plugin API v2.
 *
 * Services used to declare a globally unique id, and the configuration stored that string
 * directly. They now declare a key that is unique only within their plugin, and the host composes
 * `pluginId:instanceId:serviceKey`. Every preset and every disabled-service entry on an existing
 * installation therefore names something that no longer resolves, which would silently reset a
 * user's chosen translator to whatever came first.
 *
 * The keys below are the old ids. They are frozen: this table describes what earlier versions
 * wrote, so it should only ever grow when a *new* legacy id is discovered, never change to follow
 * a rename in the plugins themselves.
 */
internal object LegacyServiceIds {

    private const val TYPE_SENTINEL_PREFIX = "type:"

    /** Old service id to the plugin that provided it, for the ten bundled plugins. */
    private val PLUGIN_OF: Map<String, String> = mapOf(
        "ai-dictionary" to "ai-plugin",
        "ai-ocr" to "ai-plugin",
        "ai-rewriter" to "ai-plugin",
        "ai-spell-checker" to "ai-plugin",
        "ai-summarizer" to "ai-plugin",
        "ai-translator" to "ai-plugin",
        "bing-spell-checker" to "bing-services",
        "bing-translator" to "bing-services",
        "bing-tts" to "bing-services",
        "deepl-services-translator" to "deepl-services",
        "google-dictionary" to "google-services",
        "google-ocr" to "google-services",
        "google-spell-checker" to "google-services",
        "google-translator" to "google-services",
        "google-tts" to "google-services",
        "libretranslate-local-translator" to "libretranslate-services",
        "mozhi-services-translator" to "mozhi-services",
        "mymemory-services-translate" to "mymemory-services",
        "reverso-services-dictionary" to "reverso-services",
        "reverso-services-translation" to "reverso-services",
        "wikimedia-wikipedia" to "wikimedia-reference",
        "wikimedia-wiktionary" to "wikimedia-reference",
        "yandex-web-translator" to "yandex-web-services"
    )

    /**
     * The current identifier for a stored service id.
     *
     * Returns [stored] unchanged when it is already in the new form, when it is a
     * `type:CAPABILITY` sentinel, or when it names something this table does not know — a
     * third-party plugin, or one that has since been removed. Leaving an unrecognised value alone
     * loses the selection but keeps the entry legible; rewriting it to a guess would point at a
     * service that does not exist.
     *
     * Safe to run more than once, which is what makes the migration re-runnable.
     */
    fun upgrade(stored: String): String {
        if (stored.startsWith(TYPE_SENTINEL_PREFIX)) return stored
        if (ServiceId.parse(stored) != null) return stored

        val pluginId = PLUGIN_OF[stored] ?: return stored
        // The key kept the old id's text, so only the plugin and instance need adding.
        return ServiceId.of(pluginId, ServiceId.DEFAULT_INSTANCE, stored)
    }

    /** Whether [stored] names a service this table can upgrade. */
    fun isKnownLegacyId(stored: String): Boolean =
        !stored.startsWith(TYPE_SENTINEL_PREFIX) &&
            ServiceId.parse(stored) == null &&
            stored in PLUGIN_OF
}

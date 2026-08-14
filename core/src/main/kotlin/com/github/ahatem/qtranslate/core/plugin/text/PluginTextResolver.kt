package com.github.ahatem.qtranslate.core.plugin.text

import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.core.localization.LocalizationManager

/**
 * Turns a plugin's [DisplayText] into something to show the user.
 *
 * Plugins hand the host a key and a fallback rather than a finished string, so that text they
 * contribute — option labels, notifications — can be translated like the rest of the interface
 * instead of being English wherever the plugin author happened to write it.
 *
 * The lookup order is host strings, then the declaring plugin's own bundle, then the fallback.
 * Host first so a plugin cannot shadow application text by choosing a colliding key.
 */
interface PluginTextResolver {

    /** @param pluginId the plugin that supplied [text], used to find its own bundle. */
    fun resolve(pluginId: String, text: DisplayText): String

    companion object {
        /**
         * Uses only what the plugin shipped in the object itself.
         *
         * For tests and for hosts with no localization wired up. Always renders the fallback, so
         * text is readable but never translated.
         */
        val Fallback: PluginTextResolver = object : PluginTextResolver {
            override fun resolve(pluginId: String, text: DisplayText): String = text.format()
        }
    }
}

/**
 * Resolves against the application's own strings, falling back to what the plugin shipped.
 *
 * Plugin-supplied bundles are not consulted yet — that arrives with bundle loading, and this is
 * the only place that will need to change. Until then a plugin's own keys miss the host strings
 * and land on the fallback, which is exactly what the fallback is for.
 */
class LocalizedPluginTextResolver(
    private val localizationManager: LocalizationManager
) : PluginTextResolver {

    override fun resolve(pluginId: String, text: DisplayText): String {
        // getString echoes the key back when it has no translation, which is the only signal it
        // gives that the lookup missed.
        val hostString = localizationManager.getString(text.key)
        if (hostString != text.key) {
            return if (text.args.isEmpty()) hostString else hostString.format(*text.args.toTypedArray())
        }
        return text.format()
    }
}

/** Substitutes [DisplayText.args] into the fallback. */
private fun DisplayText.format(): String =
    if (args.isEmpty()) fallback else fallback.format(*args.toTypedArray())

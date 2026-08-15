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

    /**
     * A plugin has been loaded, and [classLoader] can read the files inside its JAR.
     *
     * Part of this interface rather than a second collaborator the host has to wire up alongside
     * it: a resolver that reads plugin bundles and a registry of those bundles must be the same
     * object, and two parameters could be given instances that do not match.
     */
    fun onPluginLoaded(pluginId: String, classLoader: ClassLoader) {}

    /** A plugin has been removed and anything cached for it should be dropped. */
    fun onPluginRemoved(pluginId: String) {}

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
 * Resolves against the application's strings, then the plugin's own bundle, then the fallback.
 *
 * Host first is deliberate: a plugin that happened to choose a key the application already uses
 * would otherwise replace application text with its own wherever that key appears.
 */
class LocalizedPluginTextResolver(
    private val localizationManager: LocalizationManager,
    private val pluginLocalization: PluginLocalization
) : PluginTextResolver {

    override fun onPluginLoaded(pluginId: String, classLoader: ClassLoader) =
        pluginLocalization.register(pluginId, classLoader)

    override fun onPluginRemoved(pluginId: String) = pluginLocalization.unregister(pluginId)

    override fun resolve(pluginId: String, text: DisplayText): String {
        // getString echoes the key back when it has no translation, which is the only signal it
        // gives that the lookup missed.
        val hostString = localizationManager.getString(text.key).takeIf { it != text.key }

        val translated = hostString
            ?: pluginLocalization.string(pluginId, text.key, localizationManager.activeLanguage)
            ?: text.fallback

        return if (text.args.isEmpty()) translated else translated.format(*text.args.toTypedArray())
    }
}

/** Substitutes [DisplayText.args] into the fallback. */
private fun DisplayText.format(): String =
    if (args.isEmpty()) fallback else fallback.format(*args.toTypedArray())

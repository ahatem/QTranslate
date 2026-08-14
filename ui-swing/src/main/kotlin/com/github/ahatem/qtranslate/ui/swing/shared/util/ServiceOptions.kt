package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.ServiceOption
import com.github.ahatem.qtranslate.core.localization.LocalizationManager

/**
 * One choice in a picker, with its label already resolved.
 *
 * The widgets that render these do not care whether the choice is a summary length, a rewrite
 * style, or something a plugin invented — they show a list and report back an id. Keeping the id
 * opaque is what lets a service offer a vocabulary the application has never heard of.
 */
data class ServiceOptionChoice(val id: String, val label: String)

/**
 * Resolves a [DisplayText] against the interface language, falling back to what the plugin shipped.
 *
 * [LocalizationManager.getString] echoes the key back when it has no translation, which is the only
 * signal it gives that the lookup missed.
 */
fun LocalizationManager.resolve(text: DisplayText): String {
    val translated = getString(text.key)
    val raw = if (translated == text.key) text.fallback else translated
    return if (text.args.isEmpty()) raw else raw.format(*text.args.toTypedArray())
}

/** The option with this [key], or null when the active service does not offer it. */
fun List<ServiceOption>.withKey(key: String): ServiceOption? = firstOrNull { it.key == key }

/** The option's values as rendered choices, in the order the service declared them. */
fun ServiceOption.choices(localizer: LocalizationManager): List<ServiceOptionChoice> =
    values.map { ServiceOptionChoice(it.id, localizer.resolve(it.label)) }

/**
 * The id to show as selected.
 *
 * Falls back to the option's own default when the stored id is not among its values — which
 * happens when the user switches to a service offering a different vocabulary, and is the normal
 * case rather than an error.
 */
fun ServiceOption.selectedIdOr(stored: String): String =
    if (values.any { it.id == stored }) stored else defaultValue

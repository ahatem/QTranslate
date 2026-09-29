package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import com.github.ahatem.qtranslate.core.settings.data.isServiceRoleEnabled
import com.github.ahatem.qtranslate.core.settings.data.secondaryTranslatorIds
import com.github.ahatem.qtranslate.core.settings.data.translatorPrimaryId
import com.github.ahatem.qtranslate.core.settings.data.translatorSetIds
import com.github.ahatem.qtranslate.api.plugin.ServiceRole

/** A loaded service offered for a role, or a saved id that no loaded service answers to. */
internal data class ServiceOption(val id: String, val name: String, val available: Boolean = true)

internal data class TranslatorRow(
    val id: String,
    val name: String,
    val isPrimary: Boolean,
    val available: Boolean,
    val canMakePrimary: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

internal enum class TranslatorSetHint { COMPARISON_AVAILABLE, NEEDS_ANOTHER, ROLE_DISABLED }

internal data class TranslatorSetModel(
    val rows: List<TranslatorRow>,
    /** Loaded translators that are usable and not yet members, in registry order. */
    val addable: List<ServiceOption>,
    /** Null when the set is empty: there is nothing to say about Comparison yet. */
    val hint: TranslatorSetHint?
)

/**
 * A readable stand-in for a saved id that no loaded service answers to. The service's own name is
 * unknown at that point, so the key part of a composed id is the most meaningful piece left.
 */
internal fun unavailableServiceName(id: String): String = ServiceId.parse(id)?.serviceKey ?: id

/**
 * The Settings view of the working configuration's translator set.
 *
 * Rows show configured membership, so a saved id stays visible when its service is missing or
 * disabled. Comparison readiness is not derived from the rows: it goes through
 * [isComparisonEligible], the same predicate the layout picker and runtime use.
 */
internal fun translatorSetModel(config: Configuration, installed: List<ServiceOption>): TranslatorSetModel {
    val preset = config.getActivePreset()
    val members = preset?.translatorSetIds.orEmpty()
    val secondaries = preset?.secondaryTranslatorIds.orEmpty()
    val primary = preset?.translatorPrimaryId
    val installedById = installed.associateBy { it.id }

    val rows = members.map { id ->
        val isPrimary = id == primary
        val available = id in installedById && id !in config.disabledServices
        val slot = secondaries.indexOf(id)
        TranslatorRow(
            id = id,
            name = installedById[id]?.name ?: unavailableServiceName(id),
            isPrimary = isPrimary,
            available = available,
            canMakePrimary = !isPrimary && available,
            canMoveUp = slot > 0,
            canMoveDown = slot in 0 until secondaries.lastIndex
        )
    }

    val hint = when {
        members.isEmpty() -> null
        !config.isServiceRoleEnabled(ServiceRole.TRANSLATOR) -> TranslatorSetHint.ROLE_DISABLED
        config.isComparisonEligible(installed.map { it.id }) -> TranslatorSetHint.COMPARISON_AVAILABLE
        else -> TranslatorSetHint.NEEDS_ANOTHER
    }

    return TranslatorSetModel(
        rows = rows,
        addable = installed.filter { it.id !in members && it.id !in config.disabledServices },
        hint = hint
    )
}

/**
 * The options for a one-service role: Automatic (null), the loaded services, and, when the saved
 * choice is not among them, that choice shown as unavailable so Settings does not pretend it is
 * either gone or in effect.
 */
internal fun serviceChoices(loaded: List<ServiceOption>, savedId: String?): List<ServiceOption?> {
    val stale = savedId?.takeIf { id -> loaded.none { it.id == id } }
        ?.let { ServiceOption(it, unavailableServiceName(it), available = false) }
    return listOf(null) + loaded + listOfNotNull(stale)
}

internal fun popupStartX(anchorWidth: Int, popupWidth: Int, isLeftToRight: Boolean): Int =
    if (isLeftToRight) 0 else anchorWidth - popupWidth

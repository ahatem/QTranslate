package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceRole

/** Direction of a one-step reorder among the non-primary translators. */
enum class TranslatorMove { UP, DOWN }

/** The configured Primary, whether or not it is currently available. */
val ServicePreset.translatorPrimaryId: String?
    get() = selectedServices[ServiceRole.TRANSLATOR]

/** Comparison translators with repeats and the Primary removed, in persisted order. */
val ServicePreset.secondaryTranslatorIds: List<String>
    get() = comparisonTranslatorIds.distinct().filterNot { it == translatorPrimaryId }

/**
 * The configured translator set of a preset: the Primary followed by the ordered comparison
 * translators. Membership is configuration only; availability is resolved elsewhere
 * (see [effectiveTranslatorIds]) and unavailable ids are never dropped here.
 *
 * Legacy data may list the Primary or a repeated id among the comparisons, so every operation
 * below works from this normalized view and writes it back.
 */
val ServicePreset.translatorSetIds: List<String>
    get() = listOfNotNull(translatorPrimaryId) + secondaryTranslatorIds

private fun ServicePreset.withTranslatorSet(primary: String?, secondaries: List<String>): ServicePreset =
    copy(
        selectedServices = selectedServices + (ServiceRole.TRANSLATOR to primary),
        comparisonTranslatorIds = secondaries.distinct().filterNot { it == primary }
    )

/** Adds [serviceId] as Primary when the set has none, otherwise at the end. No-op for members. */
fun ServicePreset.withTranslatorAdded(serviceId: String): ServicePreset {
    if (serviceId in translatorSetIds) return this
    return if (translatorPrimaryId == null) withTranslatorSet(serviceId, secondaryTranslatorIds)
    else withTranslatorSet(translatorPrimaryId, secondaryTranslatorIds + serviceId)
}

/**
 * Removes [serviceId] from the set. Removing the Primary promotes the first remaining member;
 * an emptied set has no Primary. No arbitrary installed translator is ever adopted.
 */
fun ServicePreset.withTranslatorRemoved(serviceId: String): ServicePreset {
    if (serviceId !in translatorSetIds) return this
    val secondaries = secondaryTranslatorIds
    return if (serviceId == translatorPrimaryId) {
        withTranslatorSet(secondaries.firstOrNull(), secondaries.drop(1))
    } else {
        withTranslatorSet(translatorPrimaryId, secondaries.filterNot { it == serviceId })
    }
}

/** Moves a non-primary member one step. The Primary, non-members and the ends are no-ops. */
fun ServicePreset.withTranslatorMoved(serviceId: String, direction: TranslatorMove): ServicePreset {
    val secondaries = secondaryTranslatorIds
    val from = secondaries.indexOf(serviceId)
    val to = if (direction == TranslatorMove.UP) from - 1 else from + 1
    if (from < 0 || to !in secondaries.indices) return this
    val moved = secondaries.toMutableList().also {
        it[from] = it[to]
        it[to] = serviceId
    }
    return withTranslatorSet(translatorPrimaryId, moved)
}

fun Configuration.withTranslatorAdded(serviceId: String): Configuration =
    withActivePreset { it.withTranslatorAdded(serviceId) }

fun Configuration.withTranslatorRemoved(serviceId: String): Configuration =
    withActivePreset { it.withTranslatorRemoved(serviceId) }

fun Configuration.withTranslatorMoved(serviceId: String, direction: TranslatorMove): Configuration =
    withActivePreset { it.withTranslatorMoved(serviceId, direction) }

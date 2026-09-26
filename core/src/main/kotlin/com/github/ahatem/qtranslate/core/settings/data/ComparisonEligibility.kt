package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceRole

/**
 * Comparison translator-set model for the parallel comparison board (#292).
 *
 * Product model: ONE translator set plus ONE canonical Primary member.
 * The set is stored as `selectedServices[TRANSLATOR]` (primary) plus the
 * ordered `comparisonTranslatorIds`; availability is resolved against the
 * loaded registry at use time, never by rewriting the stored ids.
 *
 * [translatorIds] are the loaded services holding the Translator role, in
 * registry order (what `MainState.availableServices` lists for TRANSLATOR).
 *
 * "Effective" is purely static configuration/service availability — no
 * transient network or API health. The effective set is:
 * 1. the REAL resolved canonical Primary, i.e. exactly what
 *    `ActiveServiceManager.getActive(TRANSLATOR)` returns (the preset's choice,
 *    else the first usable translator — both go through [resolveActiveServiceId]),
 * 2. then the configured comparison ids, in persisted order, that resolve, are
 *    not disabled, differ from the Primary and are not repeated.
 *
 * Only the Primary gets fallback; arbitrary installed translators are never
 * comparison members. No usable Primary (role off, or no usable translator at
 * all) means an empty set: Comparison needs a canonical translation, which
 * translation itself cannot produce without a Primary.
 *
 * Unavailable configured ids stay stored (Settings shows them as
 * unavailable/removable) but never count and are never executed.
 */
fun Configuration.effectiveTranslatorIds(translatorIds: List<String>): List<String> {
    val preset = getActivePreset() ?: return emptyList()
    val primary = resolveActiveServiceId(ServiceRole.TRANSLATOR, translatorIds) ?: return emptyList()
    val comparisons = preset.comparisonTranslatorIds.asSequence()
        .distinct()
        .filter { it != primary && it in translatorIds && !isServiceDisabled(it, ServiceRole.TRANSLATOR) }
        .toList()
    return listOf(primary) + comparisons
}

/** Number of translators actually usable for comparison right now. */
fun Configuration.effectiveTranslatorCount(translatorIds: List<String>): Int =
    effectiveTranslatorIds(translatorIds).size

/**
 * Comparison is available only with at least two effective translators.
 * Everything else (layout picker, runtime arrangement, execution fan-out,
 * Quick Translate) derives from this single predicate.
 */
fun Configuration.isComparisonEligible(translatorIds: List<String>): Boolean =
    effectiveTranslatorCount(translatorIds) >= 2

/**
 * Deterministic runtime arrangement: a requested Comparison without two
 * effective translators falls back to Classic. The saved [layoutPresetId]
 * preference is never rewritten — when Comparison becomes eligible again
 * it takes effect naturally.
 */
fun Configuration.effectiveLayoutPresetId(translatorIds: List<String>): String =
    if (layoutPresetId == LayoutPresetIds.COMPARISON &&
        !isComparisonEligible(translatorIds)
    ) {
        LayoutPresetIds.CLASSIC
    } else {
        layoutPresetId
    }

/**
 * Promotes [serviceId] to Primary while preserving the whole translator set:
 * the old Primary takes the promoted member's comparison slot and comparison
 * order is otherwise untouched. Promoting an id that is not a comparison
 * appends the old Primary at the end; promoting the current Primary only
 * drops it from the comparison list.
 */
fun ServicePreset.withPromotedTranslator(serviceId: String): ServicePreset {
    val oldPrimary = selectedServices[ServiceRole.TRANSLATOR]
    if (oldPrimary == serviceId) {
        return copy(comparisonTranslatorIds = comparisonTranslatorIds.filterNot { it == serviceId })
    }
    val swapped = comparisonTranslatorIds.map { if (it == serviceId) oldPrimary else it }
    val withOldPrimary =
        if (serviceId in comparisonTranslatorIds) swapped else swapped + listOfNotNull(oldPrimary)
    return copy(
        selectedServices = selectedServices + (ServiceRole.TRANSLATOR to serviceId),
        comparisonTranslatorIds = withOldPrimary.filterNotNull().distinct()
    )
}

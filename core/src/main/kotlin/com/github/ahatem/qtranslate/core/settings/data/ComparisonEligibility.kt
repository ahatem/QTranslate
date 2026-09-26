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
 * "Effective" is purely static configuration/service availability — no
 * transient network or API health:
 * - the id is configured,
 * - the translator resolves in the registry ([availableTranslatorIds]),
 * - the Translator role is enabled,
 * - the service itself is not disabled,
 * - ids are distinct,
 * - a resolved Primary counts as one.
 *
 * Unavailable configured ids stay stored (Settings shows them as
 * unavailable/removable) but never count and are never executed.
 */
fun Configuration.effectiveTranslatorIds(availableTranslatorIds: Set<String>): List<String> {
    val preset = getActivePreset() ?: return emptyList()
    if (!isServiceRoleEnabled(ServiceRole.TRANSLATOR)) return emptyList()
    fun isUsable(id: String): Boolean =
        id in availableTranslatorIds && !isServiceDisabled(id, ServiceRole.TRANSLATOR)
    val primary = preset.selectedServices[ServiceRole.TRANSLATOR]?.takeIf(::isUsable)
    val comparisons = preset.comparisonTranslatorIds.asSequence()
        .distinct()
        .filter { it != primary && isUsable(it) }
        .toList()
    return listOfNotNull(primary) + comparisons
}

/** Number of translators actually usable for comparison right now. */
fun Configuration.effectiveTranslatorCount(availableTranslatorIds: Set<String>): Int =
    effectiveTranslatorIds(availableTranslatorIds).size

/**
 * Comparison is available only with at least two effective translators.
 * Everything else (layout picker, runtime arrangement, execution fan-out,
 * Quick Translate) derives from this single predicate.
 */
fun Configuration.isComparisonEligible(availableTranslatorIds: Set<String>): Boolean =
    effectiveTranslatorCount(availableTranslatorIds) >= 2

/**
 * Deterministic runtime arrangement: a requested Comparison without two
 * effective translators falls back to Classic. The saved [layoutPresetId]
 * preference is never rewritten — when Comparison becomes eligible again
 * it takes effect naturally.
 */
fun Configuration.effectiveLayoutPresetId(availableTranslatorIds: Set<String>): String =
    if (layoutPresetId == LayoutPresetIds.COMPARISON &&
        !isComparisonEligible(availableTranslatorIds)
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

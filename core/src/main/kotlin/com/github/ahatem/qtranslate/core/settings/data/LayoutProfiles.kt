package com.github.ahatem.qtranslate.core.settings.data

/**
 * Rules for the list of saved layouts, kept pure so every host that saves one (the window's own
 * menus and the Settings page) answers the same way. The list is written only through
 * [withLayoutProfiles].
 */

/** The name a user typed, trimmed; null when nothing is left, which cancels a save. */
fun cleanProfileName(typed: String?): String? = typed?.trim()?.takeIf { it.isNotEmpty() }

/** The saved layout called [name], ignoring case and surrounding spaces, or null. */
fun List<LayoutProfile>.profileNamed(name: String): LayoutProfile? {
    val wanted = name.trim()
    return firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
}

/** Whether no more layouts can be added. Replacing one by name is still possible. */
val List<LayoutProfile>.isFull: Boolean get() = size >= LayoutProfile.MAX_PROFILES

/**
 * The first saved layout that equals [layout] exactly, or null. A window that differs in any
 * value (density and strip style included) matches none.
 */
fun List<LayoutProfile>.profileMatching(layout: WindowLayout): LayoutProfile? = firstOrNull { it.layout == layout }

/**
 * The list after saving [layout] as [name]: a layout with that name is replaced in place, a new
 * name is appended. Null when [name] is new and the list is full, so the caller says so instead of
 * silently dropping the save.
 */
fun List<LayoutProfile>.withProfileSaved(name: String, layout: WindowLayout): List<LayoutProfile>? {
    val clean = cleanProfileName(name) ?: return null
    val existing = profileNamed(clean)
    return when {
        existing != null -> map { if (it === existing) LayoutProfile(clean, layout) else it }
        isFull -> null
        else -> this + LayoutProfile(clean, layout)
    }
}

/**
 * The list after renaming the layout called [from] to [to]. Null when nothing is renamed: [to] is
 * empty, [from] is not in the list, or another layout already has the name (a layout may be
 * renamed to a different spelling of its own name).
 */
fun List<LayoutProfile>.withProfileRenamed(from: String, to: String): List<LayoutProfile>? {
    val clean = cleanProfileName(to) ?: return null
    val target = profileNamed(from) ?: return null
    val clash = profileNamed(clean)
    if (clash != null && clash !== target) return null
    return map { if (it === target) LayoutProfile(clean, it.layout) else it }
}

/** The list without the layout called [name]; the list itself when there is none. */
fun List<LayoutProfile>.withProfileRemoved(name: String): List<LayoutProfile> {
    val target = profileNamed(name) ?: return this
    return filterNot { it === target }
}

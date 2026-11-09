package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

/** A sidebar entry. Groups own children and have no page of their own. */
internal sealed interface Nav {
    val label: String

    data class Page(override val label: String, val iconPath: String) : Nav
    data class Group(override val label: String, val children: List<Page>) : Nav
}

/**
 * The settings sidebar's navigation structure: which pages exist, how they are grouped, and
 * which SVG each page carries. Built once from the localized labels, exactly as the sidebar
 * shows them.
 *
 * Grouped only where sections genuinely cluster. Everything under one parent would be
 * IntelliJ cosplay at this size, and a flat list left the three translation-related pages
 * separated by Plugins and Hotkeys.
 */
internal class SettingsNavigation(private val label: (String) -> String) {

    /** The sidebar, in order. */
    val tree: List<Nav> = listOf(
        Nav.Page(label("general"), Icons.GENERAL),
        Nav.Page(label("appearance"), Icons.APPEARANCE),
        Nav.Group(
            label("group_translation"), listOf(
                Nav.Page(label("services"), Icons.SERVICE),
                Nav.Page(label("behavior"), Icons.TRANSLATE),
                Nav.Page(label("languages"), Icons.LANGUAGE),
            )
        ),
        Nav.Group(
            label("group_interface"), listOf(
                Nav.Page(label("layout"), Icons.LAYOUT),
                Nav.Page(label("popups"), Icons.POPUP),
            )
        ),
        Nav.Page(label("ocr"), Icons.OCR),
        Nav.Page(label("hotkeys"), Icons.KEYBOARD),
        Nav.Page(label("plugins"), Icons.PLUGIN),
        Nav.Page(label("network"), Icons.NETWORK),
    )

    /** Every selectable page, flattened, in sidebar order. */
    val pages: List<Nav.Page> = tree.flatMap { nav ->
        when (nav) {
            is Nav.Page -> listOf(nav)
            is Nav.Group -> nav.children
        }
    }

    /** SVG resource paths keyed by localized nav label. Groups carry no icon of their own. */
    val iconPaths: Map<String, String> = pages.associate { it.label to it.iconPath }

    /** Whether [name] labels a group heading rather than a selectable page. */
    fun isGroup(name: String): Boolean = tree.any { it is Nav.Group && it.label == name }

    /**
     * The first page under the group labelled [name], or null when [name] is a page or unknown.
     *
     * Groups exist to label their children, not to hide them: selecting one opens this page,
     * which is more useful than doing nothing and avoids a selected row with a blank content area.
     */
    fun firstChildOf(name: String): Nav.Page? =
        (tree.firstOrNull { it is Nav.Group && it.label == name } as? Nav.Group)?.children?.first()
}

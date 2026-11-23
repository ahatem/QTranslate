package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.core.settings.data.Density
import javax.swing.AbstractButton

/**
 * A chrome widget whose buttons follow the window's [Density]. The window sets [density] whenever
 * it arranges its rows, and the widget keeps it across its own rebuilds.
 */
interface DensityAware {
    var density: Density
}

/**
 * The density of the chrome-row buttons, as a FlatLaf style on the button itself.
 *
 * Comfortable sets nothing, so the theme's `Button.margin` and `Button.toolbar.margin` decide.
 * Compact sets one `margin` style, which wins over the theme for that button only; nothing is
 * written to the shared defaults, so every other button keeps what the theme gives it.
 */
object ChromeButtons {
    /** Vertical and horizontal margin, in unscaled pixels, of a compact button with only an icon. */
    private const val COMPACT_ICON = "margin: 1,1,1,1"

    /** The same for a button that carries text. */
    private const val COMPACT_TEXT = "margin: 1,8,1,8"

    /** The segmented service tabs: close to their neighbours, as in the original window. */
    private const val COMPACT_TAB = "margin: 2,4,2,4"

    enum class Role(internal val compact: String) { ICON(COMPACT_ICON), TEXT(COMPACT_TEXT), TAB(COMPACT_TAB) }

    /**
     * Styles [button] for [density], keeping any other style properties it already has (a tab keeps
     * its squared corners) and replacing only its margins.
     */
    fun apply(button: AbstractButton, density: Density, role: Role) {
        val others = (button.getClientProperty(FlatClientProperties.STYLE) as? String).orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("margin") && !it.startsWith("toolbar.margin") }
        // A toolbar-type button takes its margin from `toolbar.margin` and ignores `margin`, so a
        // compact button states both.
        val style = if (density == Density.COMPACT) others + role.compact + role.compact.replace("margin", "toolbar.margin") else others
        button.putClientProperty(FlatClientProperties.STYLE, style.joinToString("; ").ifEmpty { null })
    }
}

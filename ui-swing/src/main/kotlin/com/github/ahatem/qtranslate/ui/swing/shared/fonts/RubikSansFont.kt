package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.util.FontUtils

object RubikSansFont {

    const val FAMILY = "Rubik"
    const val FAMILY_LIGHT = "Rubik Light"
    const val FAMILY_MEDIUM = "Rubik Text"
    const val FAMILY_SEMI_BOLD = "Rubik SemiBold"
    const val FAMILY_BOLD = "Rubik Bold"

    const val STYLE_REGULAR = "/fonts/sans/rubik/Rubik-Regular.ttf"
    const val STYLE_REGULAR_ITALIC = "/fonts/sans/rubik/Rubik-Italic.ttf"
    const val STYLE_MEDIUM = "/fonts/sans/rubik/Rubik-Medium.ttf"
    const val STYLE_MEDIUM_ITALIC = "/fonts/sans/rubik/Rubik-MediumItalic.ttf"
    const val STYLE_SEMI_BOLD = "/fonts/sans/rubik/Rubik-SemiBold.ttf"
    const val STYLE_SEMI_BOLD_ITALIC = "/fonts/sans/rubik/Rubik-SemiBoldItalic.ttf"
    const val STYLE_BOLD = "/fonts/sans/rubik/Rubik-Bold.ttf"
    const val STYLE_BOLD_ITALIC = "/fonts/sans/rubik/Rubik-BoldItalic.ttf"

    /** Registers the fonts for lazy loading. */
    fun installLazy() {
        FontUtils.registerFontFamilyLoader(FAMILY) { installAll() }
    }

    /** Loads all styles immediately. */
    fun install() {
        installAll()
    }

    /** Loads every available font style. */
    fun installAll() {
        listOf(
            STYLE_REGULAR,
            STYLE_REGULAR_ITALIC,
            STYLE_MEDIUM,
            STYLE_MEDIUM_ITALIC,
            STYLE_SEMI_BOLD,
            STYLE_SEMI_BOLD_ITALIC,
            STYLE_BOLD,
            STYLE_BOLD_ITALIC
        ).forEach { installStyle(it) }
    }

    /** Installs a specific font style. */
    fun installStyle(name: String): Boolean {
        val url = RubikSansFont::class.java.getResource(name)
        return url?.let { FontUtils.installFont(it) } ?: false
    }
}

package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.util.FontUtils

object IBMPlexSansFont {

    const val FAMILY = "IBM Plex Sans"
    const val FAMILY_TEXT = "IBM Plex Sans Text"
    const val FAMILY_SEMI_BOLD = "IBM Plex Sans SemiBold"
    const val FAMILY_BOLD = "IBM Plex Sans Bold"

    const val STYLE_REGULAR = "/fonts/sans/ibm/IBMPlexSans-Regular.ttf"
    const val STYLE_REGULAR_ITALIC = "/fonts/sans/ibm/IBMPlexSans-Italic.ttf"
    const val STYLE_TEXT = "/fonts/sans/ibm/IBMPlexSans-Text.ttf"
    const val STYLE_TEXT_ITALIC = "/fonts/sans/ibm/IBMPlexSans-TextItalic.ttf"
    const val STYLE_SEMI_BOLD = "/fonts/sans/ibm/IBMPlexSans-SemiBold.ttf"
    const val STYLE_SEMI_BOLD_ITALIC = "/fonts/sans/ibm/IBMPlexSans-SemiBoldItalic.ttf"
    const val STYLE_BOLD = "/fonts/sans/ibm/IBMPlexSans-Bold.ttf"
    const val STYLE_BOLD_ITALIC = "/fonts/sans/ibm/IBMPlexSans-BoldItalic.ttf"

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
            STYLE_TEXT,
            STYLE_TEXT_ITALIC,
            STYLE_SEMI_BOLD,
            STYLE_SEMI_BOLD_ITALIC,
            STYLE_BOLD,
            STYLE_BOLD_ITALIC
        ).forEach { installStyle(it) }
    }

    /** Installs a specific font style. */
    fun installStyle(name: String): Boolean {
        val url = IBMPlexSansFont::class.java.getResource(name)
        return url?.let { FontUtils.installFont(it) } ?: false
    }
}

package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.util.FontUtils

/**
 * Registers the Arabic fallback face so right-to-left text renders the same on every machine.
 *
 * Rubik has no Arabic coverage, so an Arabic translation falls through to whatever the platform
 * happens to substitute: Segoe UI on Windows, something else on Linux, and on a bare container
 * nothing at all. Registering the bundled face is what makes the result predictable.
 *
 * ### The file lives in :core
 * Document translation already bundles and embeds it for the PDF raster path, and one copy on the
 * shared classpath is better than two of the same 356 KB. This class only registers it with the
 * graphics environment, which is what the editor needs and what document translation does not.
 *
 * ### Why this one installs eagerly
 * [RubikSansFont] registers a lazy loader with FlatLaf, which is enough because the interface font
 * is requested through FlatLaf. This face is not: it is chosen by name in the editor's fallback
 * setting and built with a plain `java.awt.Font(name, ...)`, which asks the graphics environment
 * directly and never consults FlatLaf's loader. A lazily registered family would simply not be
 * found, and the substitution it exists to prevent would happen anyway.
 */
object NotoNaskhArabicFont {

    /** The family name, as it appears in the font-picker and in a saved configuration. */
    const val FAMILY: String = "Noto Naskh Arabic"

    private const val STYLE_REGULAR = "/fonts/arabic/noto/NotoNaskhArabic-Regular.ttf"
    private const val STYLE_BOLD = "/fonts/arabic/noto/NotoNaskhArabic-Bold.ttf"

    /**
     * Registers both styles with the graphics environment.
     *
     * Returns `false` when a style could not be loaded, which leaves the platform to substitute as
     * before rather than failing startup over a font.
     */
    fun install(): Boolean =
        listOf(STYLE_REGULAR, STYLE_BOLD).map(::installStyle).all { it }

    private fun installStyle(name: String): Boolean {
        val url = NotoNaskhArabicFont::class.java.getResource(name) ?: return false
        return FontUtils.installFont(url)
    }
}

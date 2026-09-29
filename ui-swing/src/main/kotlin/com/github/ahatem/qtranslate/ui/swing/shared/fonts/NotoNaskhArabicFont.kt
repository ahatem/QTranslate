package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.util.FontUtils

/**
 * The bundled Arabic face, kept as a known-good option rather than the universal fallback.
 *
 * QTranslate's default fallback is now [com.github.ahatem.qtranslate.core.settings.data.FontConfig.AUTOMATIC],
 * which asks the platform for whatever it has. This face remains available for three reasons:
 * a user may pick it explicitly, an existing configuration may already name it, and the shaped-text
 * suite uses it as a known-good Arabic fixture ([RubikSansFont]'s incomplete shaping tables are the
 * broken case that machinery was built to catch).
 *
 * ### The file lives in :core
 * Document translation already bundles and embeds it for the PDF raster path, and one copy on the
 * shared classpath is better than two of the same 356 KB. This class only registers it with the
 * graphics environment, which is what the editor needs and what document translation does not.
 *
 * ### Registering it
 * [installLazy] is what normal startup calls, the same as [RubikSansFont.installLazy]: it costs one
 * map entry, and nothing reads the font files unless something later asks for this family by name.
 * That "asking" happens in [com.github.ahatem.qtranslate.ui.swing.shared.util.toFont], which resolves a
 * saved [com.github.ahatem.qtranslate.core.settings.data.FontConfig] with a plain
 * `java.awt.Font(name, ...)` — a call that never consults FlatLaf's loader on its own, which
 * [com.github.ahatem.qtranslate.ui.swing.shared.util.toFont] compensates for by loading the family
 * first. A default installation never names this family, so it never triggers.
 */
object NotoNaskhArabicFont {

    /** The family name, as it appears in the font-picker and in a saved configuration. */
    const val FAMILY: String = "Noto Naskh Arabic"

    private const val STYLE_REGULAR = "/fonts/arabic/noto/NotoNaskhArabic-Regular.ttf"
    private const val STYLE_BOLD = "/fonts/arabic/noto/NotoNaskhArabic-Bold.ttf"

    /** Registers the fonts for lazy loading, the same as [RubikSansFont.installLazy]. */
    fun installLazy() {
        FontUtils.registerFontFamilyLoader(FAMILY) { install() }
    }

    /**
     * Registers both styles with the graphics environment immediately.
     *
     * Returns `false` when a style could not be loaded, which leaves the platform to substitute as
     * before rather than failing over a font. Exposed directly for callers — the shaped-text test
     * fixtures among them — that need the face available right away rather than on first use.
     */
    fun install(): Boolean =
        listOf(STYLE_REGULAR, STYLE_BOLD).map(::installStyle).all { it }

    private fun installStyle(name: String): Boolean {
        val url = NotoNaskhArabicFont::class.java.getResource(name) ?: return false
        return FontUtils.installFont(url)
    }
}

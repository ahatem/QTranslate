package com.github.ahatem.qtranslate.ui.swing.shared.fonts

import com.formdev.flatlaf.util.FontUtils
import java.awt.Font

/**
 * The bundled rescue faces for scripts the configured fonts would draw as missing-glyph boxes.
 *
 * A translation's script is not something a configuration can anticipate, and the fonts that cover it
 * are often optional on the host system rather than installed with it. One regular face per reported
 * script: Bengali, Devanagari and Thai.
 *
 * They are tried after the configured primary and the configured fallback, so a character the user's
 * own fonts can draw stays in them.
 */
object PortableFallbackFonts {

    const val BENGALI = "Noto Sans Bengali"
    const val DEVANAGARI = "Noto Sans Devanagari"
    const val THAI = "Noto Sans Thai"

    private class RescueFace(val family: String, private val path: String) {
        /**
         * The fonts this face has been handed out at, by size.
         *
         * Kept because callers compare fonts by identity when they merge runs, and a fresh `Font` per
         * cluster would make every character of a word look like a run of its own.
         */
        private val handedOut = mutableMapOf<Int, Font>()

        private var read = false

        /**
         * This face at [size], reading its file the first time the chain reaches it.
         *
         * A plain `Font(name, ...)` substitutes silently for a family that is not registered yet, so
         * the read has to happen before the font is built.
         */
        @Synchronized
        fun font(size: Int): Font {
            handedOut[size]?.let { return it }
            if (!read) {
                read = install(family)
            }
            return Font(family, Font.PLAIN, size).also { handedOut[size] = it }
        }

        fun install(): Boolean {
            val url = RescueFace::class.java.getResource(path) ?: return false
            return FontUtils.installFont(url)
        }
    }

    /**
     * The rescue families in chain order.
     *
     * Fixed rather than looked up by script, so the choice is the same on every machine whatever it
     * happens to have installed.
     */
    private val CHAIN = listOf(
        RescueFace(BENGALI, "/fonts/bengali/noto/NotoSansBengali-Regular.ttf"),
        RescueFace(DEVANAGARI, "/fonts/devanagari/noto/NotoSansDevanagari-Regular.ttf"),
        RescueFace(THAI, "/fonts/thai/noto/NotoSansThai-Regular.ttf"),
    )

    /** Registers the rescue faces for lazy loading, the same as [RubikSansFont.installLazy]. */
    fun installLazy() {
        CHAIN.forEach { face -> FontUtils.registerFontFamilyLoader(face.family) { face.install() } }
    }

    /**
     * Registers one rescue family with the graphics environment immediately.
     *
     * Returns `false` when the file could not be loaded, which leaves the platform to substitute as
     * before rather than failing over a font. Exposed for callers that need a face available right
     * away rather than on first use, the coverage tests among them.
     */
    fun install(family: String): Boolean = CHAIN.firstOrNull { it.family == family }?.install() ?: false

    /** Installs every rescue family. */
    fun installAll(): Boolean = CHAIN.all { it.install() }

    /**
     * The rescue faces at [size], in chain order.
     *
     * A sequence, so each face's file is read as the walk reaches it and a text the configured fonts
     * already cover reads none of them.
     */
    fun candidates(size: Int): Sequence<Font> = CHAIN.asSequence().map { it.font(size) }
}

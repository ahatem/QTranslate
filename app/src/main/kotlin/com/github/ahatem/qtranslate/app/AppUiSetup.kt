package com.github.ahatem.qtranslate.app

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledUiFont
import java.awt.Font
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.UIManager

/**
 * Handles all UI-level configuration that must be applied before any
 * Swing component is created.
 *
 * ### Call order in [main]
 * 1. [setSystemProperties] — must run before the JVM loads any AWT class.
 * 2. [setRenderingHints]   — can run any time before the first window opens.
 * 3. [apply]               — must run before [MainAppFrame] is constructed
 *                            to avoid a flash of unstyled content.
 */
object AppUiSetup {

    /**
     * Sets Java2D system properties for optimal rendering.
     *
     * **Must be called at the very top of [main], before any AWT/Swing class
     * is referenced.** System properties set after AWT initialises are ignored.
     */
    fun setSystemProperties() {
        System.setProperty("sun.awt.xembedserver",         "true")
        System.setProperty("awt.useSystemAAFontSettings",  "lcd")
        System.setProperty("swing.aatext",                 "true")
        System.setProperty("sun.java2d.opengl",            "true")
        System.setProperty("sun.java2d.d3d",               "false")
        System.setProperty("sun.java2d.noddraw",           "true")
        System.setProperty("sun.java2d.xrender",           "true")
    }

    /**
     * Registers global rendering hints with UIManager for LCD-quality text
     * anti-aliasing and high-quality rendering across all Swing components.
     */
    fun setRenderingHints() {
        UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        UIManager.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, 140)
        UIManager.put(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY)
    }

    /**
     * Applies theme, fonts, and UIManager tweaks from [config].
     *
     * Call this before constructing any Swing component — ideally immediately
     * after loading the initial configuration, before `SwingUtilities.invokeLater`.
     */
    fun apply(config: Configuration, themeManager: ThemeManager) {
        installFonts()
        applyTheme(config, themeManager)
        applyFont(config)
        applyTweaks(config)
    }

    // -------------------------------------------------------------------------

    private fun installFonts() {
        RubikSansFont.installLazy()
        FlatLaf.setPreferredFontFamily(RubikSansFont.FAMILY)
        FlatLaf.setPreferredLightFontFamily(RubikSansFont.FAMILY_LIGHT)
        FlatLaf.setPreferredSemiboldFontFamily(RubikSansFont.FAMILY_SEMI_BOLD)
    }

    private fun applyTheme(config: Configuration, themeManager: ThemeManager) {
        themeManager.applyTheme(themeManager.findThemeById(config.themeId))
    }

    private fun applyFont(config: Configuration) {
        val scaled = config.scaledUiFont
        UIManager.put(
            "defaultFont",
            FontUtils.getCompositeFont(scaled.name, Font.PLAIN, scaled.size)
        )
    }

    private fun applyTweaks(config: Configuration) {
        UIManager.put("ScrollBar.trackInsets",       Insets(2, 4, 2, 4))
        UIManager.put("ScrollBar.thumbInsets",       Insets(2, 2, 2, 2))
        UIManager.put("TitlePane.showIcon",          false)
        UIManager.put("ScrollBar.showButtons",       false)
        UIManager.put("TitlePane.unifiedBackground", config.useUnifiedTitleBar)
    }
}
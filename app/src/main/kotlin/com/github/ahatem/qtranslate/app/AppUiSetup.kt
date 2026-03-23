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

object AppUiSetup {

    fun setSystemProperties() {
        System.setProperty("sun.awt.xembedserver",         "true")
        System.setProperty("awt.useSystemAAFontSettings",  "lcd")
        System.setProperty("swing.aatext",                 "true")
        System.setProperty("sun.java2d.opengl",            "true")
        System.setProperty("sun.java2d.d3d",               "false")
        System.setProperty("sun.java2d.noddraw",           "true")
        System.setProperty("sun.java2d.xrender",           "true")
    }

    fun setRenderingHints() {
        UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        UIManager.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, 140)
        UIManager.put(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY)
    }

    fun apply(config: Configuration, themeManager: ThemeManager) {
        installFonts()
        applyTheme(config, themeManager)
        applyFont(config)
        applyTweaks(config)
    }


    private fun installFonts() {
        RubikSansFont.installLazy()
        FlatLaf.setPreferredFontFamily(RubikSansFont.FAMILY)
        FlatLaf.setPreferredLightFontFamily(RubikSansFont.FAMILY_LIGHT)
        FlatLaf.setPreferredSemiboldFontFamily(RubikSansFont.FAMILY_SEMI_BOLD)
    }

    private fun applyTheme(config: Configuration, themeManager: ThemeManager) {
        themeManager.applyThemeForStartup(themeManager.findThemeById(config.themeId))
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
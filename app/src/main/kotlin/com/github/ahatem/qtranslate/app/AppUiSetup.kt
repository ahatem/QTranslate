package com.github.ahatem.qtranslate.app

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.fonts.inter.FlatInterFont
import com.formdev.flatlaf.util.FontUtils
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.NotoNaskhArabicFont
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.RubikSansFont
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledUiFont
import java.awt.Font
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.UIManager

object AppUiSetup {

    fun setSystemProperties() {
        if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
            System.setProperty("sun.awt.xembedserver", "true")
        }
        System.setProperty("awt.useSystemAAFontSettings", "lcd")
        System.setProperty("swing.aatext", "true")
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


    /**
     * Registers every bundled face for lazy loading — Inter (the default), Rubik and Noto Naskh
     * Arabic (both retained as explicit choices; see their own classes) — and points FlatLaf at
     * Inter for the interface.
     *
     * Lazy registration is a map entry: each face's actual file is read only the first time
     * something asks for it by name, whether that is FlatLaf resolving the interface font below or
     * [com.github.ahatem.qtranslate.ui.swing.shared.util.toFont] resolving a saved editor or
     * fallback font. A fresh installation asks for "Inter" and "SansSerif" only, so Rubik and Noto
     * cost nothing here unless a configuration actually names them.
     *
     * Inter's "Light" weight is deliberately not requested: the interface has no use of FlatLaf's
     * "light" typography style class (only "h2", which is semibold, and "h4", which is bold), and
     * that pair of faces is excluded from the packaged build for exactly that reason — see the
     * shadowJar configuration in this module's build script.
     */
    private fun installFonts() {
        FlatInterFont.installLazy()
        RubikSansFont.installLazy()
        NotoNaskhArabicFont.installLazy()

        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY)
        FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD)
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

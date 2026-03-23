package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.IntelliJTheme
import com.formdev.flatlaf.extras.FlatAnimatedLafChange
import com.github.ahatem.qtranslate.api.core.Logger
import java.io.File
import java.util.Locale.getDefault

class ThemeManager(
    appDataDirectory: File,
    private val logger: Logger
) {

    private val customThemesDirectory = File(appDataDirectory, "themes")

    // @formatter:off
    private val builtInThemes: List<Theme> = listOf(
        // Custom bundled themes
        createCustomTheme("resharper_dark", "ReSharper Dark", true, "themes/ReSharperDark.theme.json"),
        createCustomTheme("resharper_light", "ReSharper Light", false, "themes/ReSharperLight.theme.json"),
        createCustomTheme("xcode_dark", "Xcode Dark", true, "themes/XcodeDark.theme.json"),
        createCustomTheme("modern_black", "VSCode Modern Black", true, "themes/vscode_dark_modern.theme.json"),
        createCustomTheme("github_dark", "GitHub Dark", true, "themes/github-dark.theme.json"),
        createCustomTheme("github_dark_dimmed", "GitHub Dark Dimmed", true, "themes/github-dark-dimmed.theme.json"),
        createCustomTheme("godot_dark", "Godot Dark", true, "themes/godot_theme.theme.json"),
        createCustomTheme("kintsugi_dark", "Kintsugi Dark", true, "themes/kintsugi.theme.json"),
        createCustomTheme("espresso_light", "Espresso Light", false, "themes/espresso_light.theme.json"),
        createCustomTheme("salmon_light", "Salmon Light", false, "themes/Salmon.theme.json"),
        createCustomTheme("cool_dark", "Cool Dark", true, "themes/cool_dark.theme.json"),
        createCustomTheme("clean_sheet", "Clean Sheet Light", false, "themes/clean_sheet.theme.json"),
        createCustomTheme("vitesse_black", "Vitesse Black", true, "themes/vitesse.black.theme.json"),
        createCustomTheme("vitesse_dark", "Vitesse Dark", true, "themes/vitesse.dark.theme.json"),
        createCustomTheme("vitesse_dark_soft", "Vitesse Dark Soft", true, "themes/vitesse.dark.soft.theme.json"),
        createCustomTheme("vitesse_light", "Vitesse Light", false, "themes/vitesse.light.theme.json"),
        createCustomTheme("vitesse_light_soft", "Vitesse Light Soft", false, "themes/vitesse.light.soft.theme.json"),

        // Built-in FlatLaf themes
        createBuiltInTheme("one_dark", "One Dark", true, "com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme"),
        createBuiltInTheme("dark_purple", "Dark Purple", true, "com.formdev.flatlaf.intellijthemes.FlatDarkPurpleIJTheme"),
        createBuiltInTheme("hiberbee_dark", "Hiberbee Dark", true, "com.formdev.flatlaf.intellijthemes.FlatHiberbeeDarkIJTheme"),
        createBuiltInTheme("material_oceanic", "Material Oceanic", true, "com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTMaterialOceanicIJTheme"),
        createBuiltInTheme("vuesion_dark", "Vuesion Dark", true, "com.formdev.flatlaf.intellijthemes.FlatVuesionIJTheme"),
        createBuiltInTheme("solarized_dark", "Solarized Dark", true, "com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme"),
        createBuiltInTheme("mac_dark", "macOS Dark", true, "com.formdev.flatlaf.themes.FlatMacDarkLaf"),
        createBuiltInTheme("mac_light", "macOS Light", false, "com.formdev.flatlaf.themes.FlatMacLightLaf"),
        createBuiltInTheme("gray_light", "Gray Light", false, "com.formdev.flatlaf.intellijthemes.FlatGrayIJTheme"),
        createBuiltInTheme("solarized_light", "Solarized Light", false, "com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme"),
        createBuiltInTheme("nord_dark", "Nord Dark", true, "com.formdev.flatlaf.intellijthemes.FlatNordIJTheme")
    )
    // @formatter:on

    private var allThemes: List<Theme>

    val defaultDarkThemeId = "custom:vitesse_dark"
    val defaultLightThemeId = "custom:vitesse_light"

    init {
        allThemes = builtInThemes
        discoverExternalThemes()
    }

    fun getAvailableThemes(): List<Theme> = allThemes

    fun findThemeById(id: String?): Theme {
        return allThemes.find { it.id == id } ?: findThemeById(defaultDarkThemeId)
    }

    fun applyTheme(theme: Theme) {
        try {
            FlatAnimatedLafChange.showSnapshot()
            theme.apply()
            FlatLaf.updateUI()
            FlatAnimatedLafChange.hideSnapshotWithAnimation()
        } catch (e: Exception) {
            logger.error("Failed to apply theme '${theme.name}' (ID: ${theme.id})", e)
            FlatAnimatedLafChange.hideSnapshotWithAnimation()
            findThemeById(defaultDarkThemeId).apply()
            FlatLaf.updateUI()
        }
    }

    fun applyThemeForStartup(theme: Theme) {
        try {
            theme.apply()
            // No FlatLaf.updateUI() here — no windows exist yet
        } catch (e: Exception) {
            logger.error("Failed to apply startup theme '${theme.name}' (ID: ${theme.id})", e)
            findThemeById(defaultDarkThemeId).apply()
        }
    }


    fun discoverExternalThemes() {
        if (!customThemesDirectory.exists() || !customThemesDirectory.isDirectory) {
            return
        }

        val externalThemes = customThemesDirectory.listFiles { _, name -> name.endsWith(".theme.json") }
            ?.mapNotNull { file ->
                try {
                    val id =
                        "external:${file.nameWithoutExtension.removeSuffix(".theme").replace(" ", "_").lowercase()}"
                    val name = file.nameWithoutExtension.replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }

                    val themeLaf = IntelliJTheme.createLaf(file.inputStream())
                    val isDark = themeLaf.isDark

                    Theme(
                        id = id,
                        name = themeLaf.name ?: name,
                        isDark = isDark,
                        apply = { FlatLaf.setup(IntelliJTheme.createLaf(file.inputStream())) }
                    )

                } catch (e: Exception) {
                    logger.error("Failed to load external theme from file: ${file.name}", e)
                    null
                }
            } ?: emptyList()

        allThemes = builtInThemes + externalThemes
    }
}
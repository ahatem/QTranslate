package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.IntelliJTheme
import com.formdev.flatlaf.extras.FlatAnimatedLafChange
import com.github.ahatem.qtranslate.api.core.Logger
import java.io.File

class ThemeManager(
    appDataDirectory: File,
    private val logger: Logger
) {

    private val customThemesDirectory = File(appDataDirectory, "themes").also { it.mkdirs() }

    // @formatter:off
    private val builtInThemes: List<Theme> = listOf(
        // ── Custom bundled themes ─────────────────────────────────────────────
        createCustomTheme("resharper_dark",      "ReSharper Dark",       true,  "themes/ReSharperDark.theme.json"),
        createCustomTheme("resharper_light",     "ReSharper Light",      false, "themes/ReSharperLight.theme.json"),
        createCustomTheme("xcode_dark",          "Xcode Dark",           true,  "themes/XcodeDark.theme.json"),
        createCustomTheme("modern_black",        "VSCode Modern Black",  true,  "themes/vscode_dark_modern.theme.json"),
        createCustomTheme("github_dark",         "GitHub Dark",          true,  "themes/github-dark.theme.json"),
        createCustomTheme("github_dark_dimmed",  "GitHub Dark Dimmed",   true,  "themes/github-dark-dimmed.theme.json"),
        createCustomTheme("godot_dark",          "Godot Dark",           true,  "themes/godot_theme.theme.json"),
        createCustomTheme("kintsugi_dark",       "Kintsugi Dark",        true,  "themes/kintsugi.theme.json"),
        createCustomTheme("espresso_light",      "Espresso Light",       false, "themes/espresso_light.theme.json"),
        createCustomTheme("salmon_light",        "Salmon Light",         false, "themes/Salmon.theme.json"),
        createCustomTheme("cool_dark",           "Cool Dark",            true,  "themes/cool_dark.theme.json"),
        createCustomTheme("clean_sheet",         "Clean Sheet Light",    false, "themes/clean_sheet.theme.json"),
        createCustomTheme("vitesse_black",       "Vitesse Black",        true,  "themes/vitesse.black.theme.json"),
        createCustomTheme("vitesse_dark",        "Vitesse Dark",         true,  "themes/vitesse.dark.theme.json"),
        createCustomTheme("vitesse_dark_soft",   "Vitesse Dark Soft",    true,  "themes/vitesse.dark.soft.theme.json"),
        createCustomTheme("vitesse_light",       "Vitesse Light",        false, "themes/vitesse.light.theme.json"),
        createCustomTheme("vitesse_light_soft",  "Vitesse Light Soft",   false, "themes/vitesse.light.soft.theme.json"),

        // ── FlatLaf IntelliJ themes ───────────────────────────────────────────
        createBuiltInTheme("one_dark",           "One Dark",              true,  "com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme"),
        createBuiltInTheme("dark_purple",        "Dark Purple",           true,  "com.formdev.flatlaf.intellijthemes.FlatDarkPurpleIJTheme"),
        createBuiltInTheme("hiberbee_dark",      "Hiberbee Dark",        true,  "com.formdev.flatlaf.intellijthemes.FlatHiberbeeDarkIJTheme"),
        createBuiltInTheme("material_oceanic",   "Material Oceanic",     true,  "com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTMaterialOceanicIJTheme"),
        createBuiltInTheme("vuesion_dark",       "Vuesion Dark",         true,  "com.formdev.flatlaf.intellijthemes.FlatVuesionIJTheme"),
        createBuiltInTheme("solarized_dark",     "Solarized Dark",       true,  "com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme"),
        createBuiltInTheme("mac_dark",           "macOS Dark",           true,  "com.formdev.flatlaf.themes.FlatMacDarkLaf"),
        createBuiltInTheme("mac_light",          "macOS Light",          false, "com.formdev.flatlaf.themes.FlatMacLightLaf"),
        createBuiltInTheme("gray_light",         "Gray Light",           false, "com.formdev.flatlaf.intellijthemes.FlatGrayIJTheme"),
        createBuiltInTheme("solarized_light",    "Solarized Light",      false, "com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme"),
        createBuiltInTheme("nord_dark",          "Nord Dark",            true,  "com.formdev.flatlaf.intellijthemes.FlatNordIJTheme"),

        // ── FlatLaf core themes (fallback-safe) ───────────────────────────────
        createBuiltInTheme("flat_light",         "Flat Light",           false, "com.formdev.flatlaf.FlatLightLaf"),
        createBuiltInTheme("flat_dark",          "Flat Dark",            true,  "com.formdev.flatlaf.FlatDarkLaf"),
        createBuiltInTheme("intellij",           "IntelliJ",             false, "com.formdev.flatlaf.FlatIntelliJLaf"),
        createBuiltInTheme("darcula",            "Darcula",              true,  "com.formdev.flatlaf.FlatDarculaLaf"),
    )
    // @formatter:on

    private var allThemes: List<Theme> = emptyList()
    private val themeCache = mutableMapOf<String, Theme>()

    private var currentTheme: Theme? = null

    val defaultDarkThemeId  = "builtin:darcula"
    val defaultLightThemeId = "builtin:intellij"

    val systemDefaultThemeId: String
        get() = if (isSystemInDarkMode()) platformDefaultDarkThemeId() else platformDefaultLightThemeId()

    init {
        reload()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    fun getAvailableThemes(): List<Theme> = allThemes

    fun getDarkThemes(): List<Theme> = allThemes.filter { it.isDark }
    fun getLightThemes(): List<Theme> = allThemes.filter { !it.isDark }

    fun getCurrentTheme(): Theme? = currentTheme

    fun findThemeById(id: String?): Theme {
        val resolvedId = if (id == OS_DEFAULT_THEME_ID) systemDefaultThemeId else id

        // Fast path – cache hit
        if (resolvedId != null && themeCache.containsKey(resolvedId)) return themeCache[resolvedId]!!

        // Slow path – linear search
        val theme = allThemes.find { it.id == resolvedId }
        if (theme != null) {
            themeCache[resolvedId!!] = theme
            return theme
        }

        // Fallback chain
        logger.warn("Theme not found: '$resolvedId' – using fallback")
        return findThemeById(
            when {
                resolvedId != null && resolvedId.contains("light", ignoreCase = true) -> defaultLightThemeId
                else -> defaultDarkThemeId
            }
        )
    }

    fun themeExists(id: String): Boolean =
        id == OS_DEFAULT_THEME_ID || themeCache.containsKey(id) || allThemes.any { it.id == id }

    // ── Apply (runtime) ─────────────────────────────────────────────────────

    fun applyTheme(theme: Theme, animate: Boolean = true) {
        if (currentTheme?.id == theme.id) {
            logger.debug("Theme already active: ${theme.name}")
            return
        }

        logger.info("Applying theme: ${theme.name}  (${theme.id})")

        val snapshot = if (animate) {
            try {
                FlatAnimatedLafChange.showSnapshot()
            } catch (_: Exception) {
                null
            }
        } else null

        try {
            theme.apply()
            FlatLaf.updateUI()
            snapshot?.let { FlatAnimatedLafChange.hideSnapshotWithAnimation() }

            currentTheme = theme
            logger.info("Theme applied: ${theme.name}")
        } catch (e: Exception) {
            logger.error("Failed to apply theme '${theme.name}'", e)
            safeHideAnimation(snapshot)
            fallbackToDefault(theme.isDark)
        }
    }

    // ── Apply (startup) ────────────────────────────────────────────────────

    fun applyThemeForStartup(theme: Theme) {
        logger.info("Startup theme: ${theme.name}  (${theme.id})")
        try {
            theme.apply()
            currentTheme = theme
        } catch (e: Exception) {
            logger.error("Startup theme failed: ${theme.name}", e)
            fallbackToDefault(theme.isDark)
        }
    }

    // ── Convenience ─────────────────────────────────────────────────────────

    fun toggleDarkLight() {
        val next = if (currentTheme?.isDark == true) findThemeById(defaultLightThemeId)
        else findThemeById(defaultDarkThemeId)
        applyTheme(next)
    }

    fun applySystemTheme() = applyTheme(findThemeById(systemDefaultThemeId))

    fun reload() {
        themeCache.clear()
        currentTheme = null
        allThemes = builtInThemes + discoverExternalThemes()
        allThemes.forEach { themeCache[it.id] = it }
        logger.info("Loaded ${allThemes.size} themes (dark=${getDarkThemes().size}, light=${getLightThemes().size})")
    }

    fun getThemeSummary(): String = buildString {
        appendLine("Themes: ${allThemes.size} total")
        appendLine("  Dark: ${getDarkThemes().size}  Light: ${getLightThemes().size}")
        appendLine("  Current: ${currentTheme?.name ?: "none"}")
        appendLine("  System: ${if (isSystemInDarkMode()) "dark" else "light"}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // External theme discovery
    // ═══════════════════════════════════════════════════════════════════════════

    fun discoverExternalThemes(): List<Theme> {
        if (!customThemesDirectory.exists()) return emptyList()

        return customThemesDirectory
            .listFiles { f -> f.isFile && f.extension == "json" && f.name.contains("theme", ignoreCase = true) }
            ?.mapNotNull { file -> loadExternalTheme(file) }
            ?: emptyList()
    }

    private fun loadExternalTheme(file: File): Theme? = try {
        val baseName = file.nameWithoutExtension
            .replace(".theme", "")
            .replace("_", " ")
            .replaceFirstChar { it.uppercase() }

        // Peek at the theme to get its real name + dark flag
        val laf = IntelliJTheme.createLaf(file.inputStream())

        Theme(
            id = "external:${file.nameWithoutExtension.lowercase().replace(" ", "_")}",
            name = laf.name ?: baseName,
            isDark = laf.isDark,
            apply = {
                file.inputStream().use { stream ->
                    FlatLaf.setup(IntelliJTheme.createLaf(stream))
                }
            }
        )
    } catch (e: Exception) {
        logger.error("Failed to load external theme: ${file.name}", e)
        null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun fallbackToDefault(isDark: Boolean) {
        val fallbackId = if (isDark) defaultDarkThemeId else defaultLightThemeId
        logger.warn("Falling back to: $fallbackId")
        try {
            val fallback = findThemeById(fallbackId)
            fallback.apply()
            FlatLaf.updateUI()
            currentTheme = fallback
        } catch (e: Exception) {
            logger.error("CRITICAL: default fallback also failed", e)
            FlatDarkLaf.setup()
            FlatLaf.updateUI()
        }
    }

    private fun safeHideAnimation(snapshot: Any?) {
        if (snapshot == null) return
        try {
            FlatAnimatedLafChange.hideSnapshotWithAnimation()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val OS_DEFAULT_THEME_ID = "os_default"

        val currentOs: String by lazy {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("win") -> "win"
                os.contains("mac") -> "mac"
                else -> "linux"
            }
        }

        fun platformDefaultDarkThemeId(): String = when (currentOs) {
            "mac" -> "builtin:mac_dark"
            else  -> "builtin:darcula"
        }

        fun platformDefaultLightThemeId(): String = when (currentOs) {
            "mac" -> "builtin:mac_light"
            else  -> "builtin:intellij"
        }

        fun isSystemInDarkMode(): Boolean = try {
            when {
                System.getProperty("os.name").lowercase().contains("win") -> {
                    // Read Windows registry
                    val proc = ProcessBuilder(
                        "reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme"
                    )
                        .redirectErrorStream(true).start()
                    !proc.inputStream.bufferedReader().readText().contains("0x1")
                }

                System.getProperty("os.name").lowercase().contains("mac") -> {
                    val proc = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                        .redirectErrorStream(true).start()
                    proc.inputStream.bufferedReader().readText().trim() == "Dark"
                }

                else -> false // Linux skipped for simplicity
            }
        } catch (_: Exception) {
            false
        }
    }
}
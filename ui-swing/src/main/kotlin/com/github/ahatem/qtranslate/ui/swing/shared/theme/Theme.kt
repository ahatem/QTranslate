package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.IntelliJTheme
import com.formdev.flatlaf.extras.FlatSVGIcon
import javax.swing.UIManager

/**
 * Represents a single, installable theme for the application.
 *
 * @property id A unique, stable, machine-readable identifier (e.g., "builtin:dark_one_dark").
 * @property name The human-readable name displayed in the UI (e.g., "One Dark").
 * @property isDark A flag indicating if the theme is dark, used for sorting and icons.
 * @property icon An optional icon to display next to the theme name.
 * @property apply A function that applies the theme to the application's UI.
 */
data class Theme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val icon: FlatSVGIcon? = null,
    val apply: () -> Unit
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return id == (other as Theme).id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Creates a Theme object for a built-in FlatLaf theme.
 *
 * @param id The unique ID for this theme (e.g., "dark_purple").
 * @param name The display name (e.g., "Dark Purple").
 * @param isDark True if it's a dark theme.
 * @param lafClassName The fully qualified class name of the FlatLaf theme.
 */
fun createBuiltInTheme(id: String, name: String, isDark: Boolean, lafClassName: String): Theme {
    return Theme(
        id = "builtin:$id",
        name = name,
        isDark = isDark,
        apply = { UIManager.setLookAndFeel(lafClassName) }
    )
}

/**
 * Creates a Theme object from a custom .theme.json file located in the resources.
 *
 * @param id The unique ID for this theme (e.g., "custom_xcode_dark").
 * @param name The display name (e.g., "Xcode Dark").
 * @param isDark True if it's a dark theme.
 * @param resourcePath The path to the .theme.json file within the application's resources (e.g., "themes/XcodeDark.theme.json").
 */
fun createCustomTheme(id: String, name: String, isDark: Boolean, resourcePath: String): Theme {
    return Theme(
        id = "custom:$id",
        name = name,
        isDark = isDark,
        apply = {
            val stream = Theme::class.java.classLoader.getResourceAsStream(resourcePath)
            if (stream != null) {
                FlatLaf.setup(IntelliJTheme.createLaf(stream))
            } else {
                println("ERROR: Theme resource not found at path: $resourcePath")
            }
        }
    )
}
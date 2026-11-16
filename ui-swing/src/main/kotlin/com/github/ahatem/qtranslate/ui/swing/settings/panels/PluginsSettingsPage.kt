package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.border.MatteBorder

/**
 * The Plugins page: the plugin manager, inside Settings, with its immediacy stated at the top.
 *
 * ### Why it is a page again
 *
 * An earlier revision moved the whole manager into its own window and left Settings holding a link
 * to it. That solved a real problem — the list used to sit inside a dialog whose Apply/Cancel
 * implied it could undo an install — but it made plugins something the user has to be sent *to*,
 * and Settings is where people look for them. IntelliJ keeps plugins in Settings for the same
 * reason.
 *
 * The honest way to keep both properties is to say what is true, on the page: everything here takes
 * effect immediately, Apply and Cancel do not govern it, and closing the dialog with Cancel cannot
 * undo an install. The list is then free to live where it belongs, next to the rest of the
 * application's configuration.
 *
 * The manager itself is [PluginsPanel] — search, categories, install, drag-and-drop, a per-plugin
 * enable/disable toggle in the row, detail pane and Configure for the plugin's own settings. This
 * class only frames it; there is deliberately one implementation, not two.
 */
internal class PluginsSettingsPage(
    localizationManager: LocalizationManager,
    iconManager: IconManager,
    pluginManager: PluginManager,
    scope: CoroutineScope,
) : JPanel(BorderLayout()), UnscrolledSettingsPage {

    init {
        isOpaque = false
        add(buildNotice(localizationManager), BorderLayout.NORTH)
        add(PluginsPanel(iconManager, pluginManager, localizationManager, scope), BorderLayout.CENTER)
    }

    /**
     * One line above the list, saying what Apply and Cancel do and do not do here.
     *
     * A page inside this dialog has to be explicit about it: every other page is staged and
     * discardable, and a plugin whose enable took effect the moment it was clicked needs to say so
     * rather than rely on the reader noticing there was no Apply.
     */
    private fun buildNotice(localizationManager: LocalizationManager): JPanel {
        val edge = UIScale.scale(SettingsPanel.EDGE)
        val text = localizationManager.getString("settings_plugins.manager_hint")
        val label = JLabel(text).apply {
            font = SettingsVisuals.hintFont(font)
            foreground = SettingsVisuals.secondaryText()
            // A single line clips silently in a narrow window; the hover still says all of it.
            toolTipText = text
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                MatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY),
                BorderFactory.createEmptyBorder(
                    UIScale.scale(6), edge, UIScale.scale(6), edge,
                ),
            )
            add(label, BorderLayout.CENTER)
        }
    }
}

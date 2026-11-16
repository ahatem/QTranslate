package com.github.ahatem.qtranslate.ui.swing.settings

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.ui.swing.settings.panels.PluginsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Dialog.ModalityType
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.UIManager
import javax.swing.border.MatteBorder

/**
 * Plugin management, deliberately outside the Apply/Cancel flow.
 *
 * Installing, enabling, disabling, updating and removing a plugin are filesystem and lifecycle
 * operations that happen when they are clicked: no draft, and nothing for Cancel to undo. Hosting
 * them inside the Settings dialog meant a Cancel button that appeared to offer a way back and did
 * not, and an Apply button that stayed disabled because the change had already been made.
 *
 * The panel itself is [PluginsPanel], unchanged; what moves is the contract around it. It is
 * wrapped in a scroll pane because it asks for exactly the viewport's size and scrolls
 * internally, the same way the Settings dialog hosted it.
 */
class PluginManagerDialog(
    owner: Window,
    iconManager: IconManager,
    pluginManager: PluginManager,
    localizationManager: LocalizationManager,
    scope: CoroutineScope,
) : JDialog(owner, localizationManager.getString("settings_plugins.manager_title"), ModalityType.APPLICATION_MODAL) {

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = BorderLayout()

        add(
            JScrollPane(
                PluginsPanel(iconManager, pluginManager, localizationManager, scope)
            ).apply { border = BorderFactory.createEmptyBorder() },
            BorderLayout.CENTER,
        )

        add(
            buildButtonBar(localizationManager.getString("common.close")),
            BorderLayout.SOUTH,
        )

        minimumSize = Dimension(UIScale.scale(820), UIScale.scale(560))
        preferredSize = Dimension(UIScale.scale(980), UIScale.scale(700))
        pack()
        setLocationRelativeTo(owner)
    }

    /**
     * The dialog's action bar, built the way the Settings dialog builds its own.
     *
     * It used to be a bare `FlowLayout` panel: no separator above it and no padding round the
     * button, so the bar read as part of the plugin list rather than as the dialog's footer, and
     * the two dialogs — one opened from the other — had visibly different bottoms. The rule uses
     * `Component.borderColor` and the padding matches the Settings bar, so the two line up as the
     * same piece of chrome in whichever theme is active.
     */
    private fun buildButtonBar(closeLabel: String): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY),
            BorderFactory.createEmptyBorder(10, 10, 10, 10),
        )
        add(
            JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
                isOpaque = false
                add(
                    JButton(closeLabel).apply {
                        addActionListener { dispose() }
                    }
                )
            },
            BorderLayout.CENTER,
        )
    }
}

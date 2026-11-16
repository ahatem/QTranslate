package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import java.awt.GridBagConstraints
import javax.swing.JButton

/**
 * The Settings entry point for plugin management.
 *
 * Plugins are not settings: their lifecycle changes take effect immediately and Cancel cannot
 * undo them. So this page holds no plugin controls at all — it says what the manager does and
 * opens it. Keeping the real panel here would have left a page whose buttons ignore Apply and
 * Cancel, which is exactly the confusion this program is meant to remove.
 */
class PluginManagerLinkPanel(
    private val localizationManager: LocalizationManager,
    private val onOpenPluginManager: () -> Unit,
) : SettingsPanel() {

    init { buildUI() }

    private fun buildUI() {
        addSeparator(localizationManager.getString("settings_dialog_sidebar.plugins"))
        addHint(localizationManager.getString("settings_plugins.manager_hint"))

        val open = JButton(localizationManager.getString("settings_plugins.manager_open")).apply {
            addActionListener { onOpenPluginManager() }
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(open)
        registerSearchEntry(localizationManager.getString("settings_plugins.manager_open"), open)

        finishLayout()
    }

    /** This page holds no setting of its own, so there is nothing to render or reset. */
    override fun render(state: SettingsState) = Unit
}

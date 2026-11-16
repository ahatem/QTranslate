package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.formdev.flatlaf.util.UIScale
import java.awt.GridBagConstraints
import javax.swing.JLabel

/**
 * The Settings entry point for plugin management.
 *
 * ### Why this is a page and not the manager
 *
 * Plugin lifecycle changes take effect immediately and Cancel cannot undo them, so they cannot be
 * part of the draft this dialog edits. Hosting the real manager here would leave a page whose
 * buttons ignore Apply and Cancel — exactly the confusion this program exists to remove — so the
 * manager opens in its own window ([com.github.ahatem.qtranslate.ui.swing.settings.PluginManagerDialog])
 * and this page explains the arrangement and hands over to it.
 *
 * ### Why it looks like this
 *
 * It was originally one dimmed sentence and a default-sized button, which is honest but reads as
 * an unfinished placeholder: a page that exists only to say "not here". The owner asked for the
 * sidebar entry to stay and for the page to feel intentional, so it now has the structure every
 * other page has — a section heading, prose that says what plugins *are*, the live state of the
 * installation, and one prominent action — and no plugin controls, because a control here would
 * be a control that lies about Apply.
 */
class PluginManagerLinkPanel(
    private val localizationManager: LocalizationManager,
    private val onOpenPluginManager: () -> Unit,
    /**
     * One line about what is installed right now, or blank when nothing is loaded yet.
     *
     * Read on every render rather than at construction: plugins can be installed from the manager
     * this page opens, and a count frozen at page build time would be wrong the moment the user
     * came back from it.
     */
    private val pluginSummary: () -> String = { "" },
) : SettingsPanel() {

    private lateinit var statusLabel: JLabel

    init { buildUI() }

    private fun buildUI() {
        addSeparator(localizationManager.getString("settings_plugins.manager_title"))

        addParagraph(localizationManager.getString("settings_plugins.page_description"))

        statusLabel = JLabel().apply {
            font = SettingsVisuals.bodyFont(font)
            foreground = SettingsVisuals.secondaryText()
            isVisible = false
        }
        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 0, UIScale.scale(2), 0)
            .add(statusLabel)

        addPrimaryButton(localizationManager.getString("settings_plugins.manager_open")) {
            onOpenPluginManager()
        }

        // The one thing a page that opens another window owes the reader: that the other window's
        // changes are not part of this dialog's Apply/Cancel.
        addHint(localizationManager.getString("settings_plugins.manager_hint"))

        // Without this the rows have no weight to absorb the page's leftover height, and GridBag
        // centres the whole block in the content area instead of starting it at the top. The page
        // looked like it was floating mid-window until the screenshot showed it.
        finishLayout()
    }

    override fun render(state: SettingsState) {
        val summary = pluginSummary()
        statusLabel.text = summary
        statusLabel.isVisible = summary.isNotBlank()
        statusLabel.revalidate()
    }
}

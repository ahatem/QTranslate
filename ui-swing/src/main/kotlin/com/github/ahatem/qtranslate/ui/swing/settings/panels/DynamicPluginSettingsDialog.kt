package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.ServiceValidation
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsModel
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.BorderLayout
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * A plugin's own settings in a window of their own.
 *
 * Only used where the request arrives from outside the settings dialog — today the service menu
 * on the main window, which has no page to reveal a form on. Inside Settings the same
 * [PluginSettingsForm] is shown inline on the Plugins page, so the two entry points cannot drift
 * apart: this class adds a window around that form and nothing else.
 *
 * The window's buttons are the form's own Save and Test plus Cancel, on the form's single button
 * row, so the layout matches what the Plugins page shows for the same plugin.
 */
class DynamicPluginSettingsDialog(
    owner: Window?,
    pluginName: String,
    localizationManager: LocalizationManager,
    settingsModel: PluginSettingsModel,
    /** Live settings instance for @PluginAction invocation. May be null for plugins without actions. */
    settingsInstance: PluginSettings.Configurable? = null,
    /** Applies the values on screen; the window closes only when it succeeds. */
    onSave: suspend (Map<String, String>, Set<String>) -> Result<Unit, ServiceError>,
    /** Applies the values and reports what each service answered. Null hides the Test button. */
    onTestConnection: (suspend (Map<String, String>) -> List<ServiceValidation>)? = null,
) : JDialog(
    owner,
    localizationManager.getString("plugin_config.title_format").format(pluginName),
    ModalityType.APPLICATION_MODAL
) {

    /** Cancelled with the window, so a save or a check cannot outlive it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        layout = BorderLayout()

        val form = PluginSettingsForm(
            localizationManager = localizationManager,
            settingsModel = settingsModel,
            settingsInstance = settingsInstance,
            scope = scope,
            onSave = onSave,
            onSaved = { dispose() },
            // The window already has a Cancel that abandons the edits by closing it; a second
            // control for the same outcome would be two words for one idea.
            onRevert = null,
            onTestConnection = onTestConnection,
            scrollable = true,
        )
        form.addFooterAction(
            JButton(localizationManager.getString("common.cancel")).apply {
                addActionListener { dispose() }
            }
        )

        val body = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(14), UIScale.scale(16), UIScale.scale(12), UIScale.scale(16)
            )
            add(form, BorderLayout.CENTER)
        }

        add(body, BorderLayout.CENTER)

        rootPane.defaultButton = form.saveButton
        rootPane.registerKeyboardAction(
            { dispose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = java.awt.Dimension(520, 320)
        preferredSize = java.awt.Dimension(UIScale.scale(640), UIScale.scale(500))
        pack()
        setLocationRelativeTo(owner)
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }
}

package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * How the application reaches the network: the proxy, how long it waits, and how hard it tries.
 *
 * ### Why these are here rather than on each plugin
 * A proxy is a property of where the user is sitting. So is a timeout, mostly. Neither is a
 * property of which translation service is selected, and before this page there was nowhere to say
 * so once: a corporate proxy meant every plugin needing its own answer, which is to say no answer
 * at all. Plugins are handed a client already configured from this and never see it.
 *
 * ### What applies when
 * Everything here takes effect for clients built after it is saved. A plugin already running keeps
 * the client it was given, which is why the page says so rather than pretending otherwise.
 */
class NetworkPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    /**
     * Reads and writes the proxy password, which lives in the secret store rather than the
     * configuration file. Absent in contexts that have no secret store, which hides the field
     * rather than offering one that forgets what is typed into it.
     */
    private val proxyPassword: PasswordAccess? = null
) : SettingsPanel() {

    /** Somewhere to keep a password that is not the configuration file. */
    interface PasswordAccess {
        fun read(): String
        fun write(value: String)
    }

    private lateinit var proxyEnabled: JCheckBox
    private lateinit var proxyUrlField: JTextField
    private lateinit var proxyUserField: JTextField
    private var proxyPasswordField: JPasswordField? = null

    private lateinit var requestTimeout: JSpinner
    private lateinit var connectTimeout: JSpinner
    private lateinit var socketTimeout: JSpinner
    private val envelopeWarning = JLabel()
    private val hostTimeoutsSummary = JLabel()
    /** Host to seconds, edited in its own window and written back on OK. */
    private val hostTimeouts = linkedMapOf<String, Int>()

    private lateinit var retryEnabled: JCheckBox
    private lateinit var maxRetries: JSpinner
    private lateinit var retryDelay: JSpinner

    private lateinit var perHostCap: JSpinner
    private lateinit var totalCap: JSpinner

    init { buildUI() }

    private fun buildUI() {
        // ── Proxy ─────────────────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_network.proxy_group"))

        proxyEnabled = addCheckbox(
            text = localizationManager.getString("settings_network.proxy_enabled"),
            selected = false,
            onChange = { enabled ->
                updateProxyFieldsEnabled(enabled)
                applyNetwork { it.copy(proxyEnabled = enabled) }
            }
        )

        proxyUrlField = JTextField().apply {
            putClientProperty(
                FlatClientProperties.PLACEHOLDER_TEXT,
                localizationManager.getString("settings_network.proxy_url_placeholder")
            )
            onEdit { applyNetwork { config -> config.copy(proxyUrl = text.trim()) } }
        }
        addRow(localizationManager.getString("settings_network.proxy_url"), proxyUrlField)

        proxyUserField = JTextField().apply {
            onEdit { applyNetwork { config -> config.copy(proxyUsername = text.trim()) } }
        }
        addRow(localizationManager.getString("settings_network.proxy_username"), proxyUserField)

        proxyPassword?.let { access ->
            proxyPasswordField = JPasswordField().apply {
                text = access.read()
                // Written straight through to the secret store rather than into the draft
                // configuration, so it never travels with the rest of the settings and never
                // reaches the JSON on disk.
                onEdit { access.write(String(password)) }
            }
            addRow(localizationManager.getString("settings_network.proxy_password"), proxyPasswordField!!)
        }
        addHint(localizationManager.getString("settings_network.proxy_hint"))

        // ── Timeouts ──────────────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_network.timeouts_group"))

        requestTimeout = secondsSpinner { seconds -> applyNetwork { it.copy(requestTimeoutSeconds = seconds) } }
        connectTimeout = secondsSpinner { seconds -> applyNetwork { it.copy(connectTimeoutSeconds = seconds) } }
        socketTimeout = secondsSpinner { seconds -> applyNetwork { it.copy(socketTimeoutSeconds = seconds) } }

        addRow(
            localizationManager.getString("settings_network.request_timeout"),
            withSecondsSuffix(requestTimeout, "settings_network.request_timeout_info")
        )
        addRow(
            localizationManager.getString("settings_network.connect_timeout"),
            withSecondsSuffix(connectTimeout, "settings_network.connect_timeout_info")
        )
        addRow(
            localizationManager.getString("settings_network.socket_timeout"),
            withSecondsSuffix(socketTimeout, "settings_network.socket_timeout_info")
        )
        addHint(localizationManager.getString("settings_network.timeouts_hint"))

        envelopeWarning.apply {
            icon = ScaledWarningIcon()
            iconTextGap = 5
            foreground = UIManager.getColor("Component.warning.focusedBorderColor")
                ?: UIManager.getColor("Label.foreground")
            font = font.deriveFont(font.size - 1f)
            isVisible = false
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(2, 2, 4, 0).add(envelopeWarning)

        addRow(
            localizationManager.getString("settings_network.host_timeouts"),
            compact(
                hostTimeoutsSummary,
                JButton(localizationManager.getString("settings_network.host_timeouts_edit")).apply {
                    putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
                    addActionListener { editHostTimeouts() }
                },
                info("settings_network.host_timeouts_info")
            )
        )

        // ── Retries ───────────────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_network.retry_group"))

        retryEnabled = addCheckbox(
            text = localizationManager.getString("settings_network.retry_enabled"),
            selected = true,
            onChange = { enabled ->
                maxRetries.isEnabled = enabled
                retryDelay.isEnabled = enabled
                applyNetwork { it.copy(retryEnabled = enabled) }
            }
        )
        maxRetries = JSpinner(SpinnerNumberModel(2, 0, 10, 1)).apply {
            (editor as? JSpinner.NumberEditor)?.textField?.columns = 3
            addChangeListener { if (!isUpdatingFromState) applyNetwork { it.copy(maxRetries = value as Int) } }
        }
        addRow(localizationManager.getString("settings_network.max_retries"), compact(maxRetries, info("settings_network.max_retries_info")))
        retryDelay = JSpinner(SpinnerNumberModel(1, 1, 60, 1)).apply {
            (editor as? JSpinner.NumberEditor)?.textField?.columns = 3
            addChangeListener {
                if (!isUpdatingFromState) applyNetwork { it.copy(retryInitialDelaySeconds = value as Int) }
            }
        }
        addRow(
            localizationManager.getString("settings_network.retry_delay"),
            compact(
                retryDelay,
                mutedLabel(localizationManager.getString("settings_network.seconds_suffix")),
                info("settings_network.retry_delay_info")
            )
        )
        addHint(localizationManager.getString("settings_network.retry_hint"))

        // ── Connections ───────────────────────────────────────────────────────
        addSeparator(localizationManager.getString("settings_network.connections_group"))

        perHostCap = JSpinner(SpinnerNumberModel(8, 1, 64, 1)).apply {
            (editor as? JSpinner.NumberEditor)?.textField?.columns = 3
            addChangeListener {
                if (!isUpdatingFromState) applyNetwork { it.copy(maxConnectionsPerHost = value as Int) }
            }
        }
        totalCap = JSpinner(SpinnerNumberModel(64, 1, 512, 1)).apply {
            (editor as? JSpinner.NumberEditor)?.textField?.columns = 4
            addChangeListener {
                if (!isUpdatingFromState) applyNetwork { it.copy(maxConnectionsTotal = value as Int) }
            }
        }
        addRow(localizationManager.getString("settings_network.per_host_connections"), compact(perHostCap, info("settings_network.per_host_connections_info")))
        addRow(localizationManager.getString("settings_network.total_connections"), compact(totalCap, info("settings_network.total_connections_info")))
        addHint(localizationManager.getString("settings_network.connections_hint"))

        addSeparator(localizationManager.getString("settings_network.applies_group"))
        addHint(localizationManager.getString("settings_network.applies_hint"))

        finishLayout()
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    /** Edits the network block of the draft, leaving the rest of the configuration alone. */
    private fun applyNetwork(edit: (NetworkConfig) -> NetworkConfig) {
        if (isUpdatingFromState) return
        applyDraft(store) { it.copy(network = edit(it.network)) }
    }

    private fun secondsSpinner(onChange: (Int) -> Unit) =
        JSpinner(SpinnerNumberModel(30, 1, 600, 5)).apply {
            (editor as? JSpinner.NumberEditor)?.textField?.columns = 4
            addChangeListener {
                if (!isUpdatingFromState) onChange(value as Int)
                updateEnvelopeWarning()
            }
        }

    /**
     * A small (i) carrying the explanation for the control beside it.
     *
     * These four numbers all sound like each other and are not: a whole-request timeout is not the
     * sum of a connect and a socket timeout, and someone raising the wrong one gets no benefit and
     * no error either. The hint under each group says what the group is for; this says what the
     * individual number does, which is where the confusion actually lives.
     */
    private fun info(key: String): JComponent =
        JLabel(runCatching<javax.swing.Icon?> {
            // Ten, not the fourteen the toolbar icons use. This glyph is a circle filling its whole
            // viewBox where a pen or a plus leaves whitespace around itself, so at a matching
            // nominal size it reads as much larger than they do. Muted too: it is an aside, and it
            // sat brighter than the number it was explaining.
            FlatSVGIcon("icons/lucide/info.svg", UIScale.scale(10), UIScale.scale(10), javaClass.classLoader)
                .apply {
                    colorFilter = FlatSVGIcon.ColorFilter {
                        UIManager.getColor("Label.disabledForeground") ?: it
                    }
                }
        }.getOrNull()).apply {
            toolTipText = "<html><body style='width:280px'>" +
                localizationManager.getString(key).replace("<", "&lt;") + "</body></html>"
        }

    private fun withSecondsSuffix(spinner: JSpinner, infoKey: String): JComponent =
        compact(
            spinner,
            mutedLabel(localizationManager.getString("settings_network.seconds_suffix")),
            info(infoKey)
        )

    /**
     * Keeps a spinner at the width it asks for.
     *
     * A row fills its component horizontally, which is right for a text field and absurd for a
     * number: a spinner for "attempts after the first" stretched the width of the dialog while the
     * timeout spinners beside it stayed small, only because those happened to be wrapped for their
     * unit label. Wrapping them all makes the row consistent rather than accidentally so.
     */
    private fun compact(vararg parts: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
            isOpaque = false
            parts.forEach { add(it) }
        }

    private fun mutedLabel(text: String) = javax.swing.JLabel(text).apply {
        foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        font = font.deriveFont(font.size - 1f)
    }

    private fun updateProxyFieldsEnabled(enabled: Boolean) {
        proxyUrlField.isEnabled = enabled
        proxyUserField.isEnabled = enabled
        proxyPasswordField?.isEnabled = enabled
    }


    /**
     * Says so when the whole-request timeout is below one of the two it contains.
     *
     * The three are not additive: connecting and waiting-for-data both run inside the whole-request
     * envelope, so setting the envelope smaller than either does not shorten that phase, it makes
     * it unreachable. The setting stays where it was put and this explains what it now means,
     * rather than the page silently raising a number the user did not touch.
     */
    private fun updateEnvelopeWarning() {
        val whole = requestTimeout.value as? Int ?: return
        val connect = connectTimeout.value as? Int ?: return
        val socket = socketTimeout.value as? Int ?: return
        val shadowed = maxOf(connect, socket)

        envelopeWarning.isVisible = whole < shadowed
        if (envelopeWarning.isVisible) {
            envelopeWarning.text =
                localizationManager.getString("settings_network.envelope_warning", whole, shadowed)
        }
    }

    private fun updateHostTimeoutsSummary() {
        hostTimeoutsSummary.text = when (hostTimeouts.size) {
            0 -> localizationManager.getString("settings_network.host_timeouts_none")
            else -> localizationManager.getString("settings_network.host_timeouts_count", hostTimeouts.size)
        }
        hostTimeoutsSummary.foreground = UIManager.getColor(
            if (hostTimeouts.isEmpty()) "Label.disabledForeground" else "Label.foreground"
        )
        hostTimeoutsSummary.toolTipText = hostTimeouts.entries
            .joinToString("<br>") { "${it.key}: ${it.value}s" }
            .takeIf { it.isNotEmpty() }
            ?.let { "<html>$it</html>" }
    }

    /**
     * The per-host list, in a window with room for it.
     *
     * Not inline: this is empty for almost everyone and unbounded for the few who use it, which is
     * the same shape as the translator credits and gets the same answer. The settings row states
     * how many there are and the editing happens somewhere that can grow.
     */
    private fun editHostTimeouts() {
        val model = object : javax.swing.table.DefaultTableModel(
            arrayOf(
                localizationManager.getString("settings_network.host_column"),
                localizationManager.getString("settings_network.seconds_column")
            ),
            0
        ) {
            override fun getColumnClass(columnIndex: Int): Class<*> =
                if (columnIndex == 1) Integer::class.java else String::class.java
        }
        hostTimeouts.forEach { (host, seconds) -> model.addRow(arrayOf<Any>(host, seconds)) }

        val table = javax.swing.JTable(model).apply {
            rowHeight = UIScale.scale(24)
            putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true")
        }
        val removeButton = JButton(localizationManager.getString("settings_network.host_remove")).apply {
            isEnabled = false
            addActionListener {
                val row = table.selectedRow
                if (row >= 0) {
                    if (table.isEditing) table.cellEditor?.stopCellEditing()
                    model.removeRow(row)
                }
            }
        }
        table.selectionModel.addListSelectionListener { removeButton.isEnabled = table.selectedRow >= 0 }

        val addButton = JButton(localizationManager.getString("settings_network.host_add")).apply {
            addActionListener {
                model.addRow(arrayOf<Any>("", 60))
                val row = model.rowCount - 1
                table.setRowSelectionInterval(row, row)
                table.editCellAt(row, 0)
                table.editorComponent?.requestFocusInWindow()
            }
        }

        val panel = JPanel(java.awt.BorderLayout(UIScale.scale(8), UIScale.scale(8))).apply {
            preferredSize = java.awt.Dimension(UIScale.scale(380), UIScale.scale(260))
            add(javax.swing.JScrollPane(table).apply {
                border = javax.swing.BorderFactory.createLineBorder(
                    UIManager.getColor("Component.borderColor") ?: java.awt.Color.GRAY
                )
            }, java.awt.BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, UIScale.scale(6), 0)).apply {
                isOpaque = false
                add(addButton)
                add(removeButton)
            }, java.awt.BorderLayout.SOUTH)
        }

        val result = javax.swing.JOptionPane.showConfirmDialog(
            this, panel,
            localizationManager.getString("settings_network.host_timeouts_title"),
            javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE
        )
        if (result != javax.swing.JOptionPane.OK_OPTION) return
        // A cell still being edited has not written its value back to the model yet, and OK while
        // typing would otherwise drop whatever was just entered.
        if (table.isEditing) table.cellEditor?.stopCellEditing()

        val edited = linkedMapOf<String, Int>()
        for (row in 0 until model.rowCount) {
            val host = (model.getValueAt(row, 0) as? String)?.trim().orEmpty()
            val seconds = (model.getValueAt(row, 1) as? Number)?.toInt() ?: continue
            // A half-filled row is dropped rather than saved as an entry matching nothing.
            if (host.isNotEmpty()) edited[host] = seconds.coerceIn(1, 3600)
        }
        if (edited != hostTimeouts) {
            hostTimeouts.clear()
            hostTimeouts.putAll(edited)
            updateHostTimeoutsSummary()
            applyNetwork { it.copy(hostTimeoutSeconds = edited.toMap()) }
        }
    }

    /** FlatLaf's warning glyph, drawn at the size a hint line wants rather than a dialog's. */
    private inner class ScaledWarningIcon : javax.swing.Icon {
        private val delegate = com.formdev.flatlaf.icons.FlatOptionPaneWarningIcon()
        private val side = UIScale.scale(13)
        override fun getIconWidth() = side
        override fun getIconHeight() = side
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) {
            val g2 = (g?.create() as? java.awt.Graphics2D) ?: return
            try {
                g2.translate(x, y)
                g2.scale(side.toDouble() / delegate.iconWidth, side.toDouble() / delegate.iconHeight)
                delegate.paintIcon(c, g2, 0, 0)
            } finally {
                g2.dispose()
            }
        }
    }

    override fun render(state: SettingsState) {
        val network = state.workingConfiguration.network
        withoutTrigger {
            proxyEnabled.isSelected = network.proxyEnabled
            proxyUrlField.text = network.proxyUrl
            proxyUserField.text = network.proxyUsername
            updateProxyFieldsEnabled(network.proxyEnabled)

            requestTimeout.value = network.requestTimeoutSeconds.coerceIn(1, 600)
            connectTimeout.value = network.connectTimeoutSeconds.coerceIn(1, 600)
            socketTimeout.value = network.socketTimeoutSeconds.coerceIn(1, 600)

            retryEnabled.isSelected = network.retryEnabled
            maxRetries.value = network.maxRetries.coerceIn(0, 10)
            maxRetries.isEnabled = network.retryEnabled
            retryDelay.value = network.retryInitialDelaySeconds.coerceIn(1, 60)
            retryDelay.isEnabled = network.retryEnabled

            perHostCap.value = network.maxConnectionsPerHost.coerceIn(1, 64)
            totalCap.value = network.maxConnectionsTotal.coerceIn(1, 512)

            hostTimeouts.clear()
            hostTimeouts.putAll(network.hostTimeoutSeconds)
            updateHostTimeoutsSummary()
            updateEnvelopeWarning()
        }
    }

    private fun javax.swing.text.JTextComponent.onEdit(action: () -> Unit) {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = action()
            override fun removeUpdate(e: DocumentEvent?) = action()
            override fun changedUpdate(e: DocumentEvent?) = action()
        })
    }
}

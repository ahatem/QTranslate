package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.settings.*
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import java.awt.*
import javax.swing.Scrollable
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * A dialog that builds its form dynamically from a [PluginSettingsModel] schema.
 *
 * ### Layout
 * Two-column grid: fixed-width label column (max 160 px) on the left, expanding
 * component column on the right. Components fill the available width — no fixed
 * character-count constructors are used so nothing overflows the dialog bounds.
 *
 * ### Validation
 * Required fields are marked with a red `*`. On save, empty required fields get
 * a red border applied inline — the user can see exactly which fields need attention
 * without reading an error message and mentally scanning back to find them.
 */
class DynamicPluginSettingsDialog(
    owner: Window?,
    pluginName: String,
    private val localizationManager: LocalizationManager,
    private val settingsModel: PluginSettingsModel,
    private val onSave: (Map<String, String>) -> Unit
) : JDialog(owner,     localizationManager.getString("plugin_config.title_format").format(pluginName), ModalityType.APPLICATION_MODAL) {

    private val components = mutableMapOf<String, SettingComponent>()

    init {
        layout = BorderLayout()

        // ---- Form ----
        // ScrollablePanel tells JScrollPane to match its width to the viewport width
        // rather than its own preferred width. Without this, components like JTextField
        // report a large preferred width that the scroll pane happily uses, making
        // inputs overflow the dialog. With getScrollableTracksViewportWidth() = true,
        // fill=HORIZONTAL in GridBagConstraints works as expected.
        val formPanel = object : JPanel(GridBagLayout()), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
            override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = 64
            override fun getScrollableTracksViewportWidth()  = true   // ← the key line
            override fun getScrollableTracksViewportHeight() = false
        }.apply {
            border = BorderFactory.createEmptyBorder(16, 16, 8, 16)
        }

        // Single full-width column: label on top, input below, hint below that.
        // Each setting occupies one row. This gives inputs the full dialog width
        // regardless of label length, which is better for plugin settings that
        // often have long labels like "Service Account JSON Path".
        val rowConstraints = GridBagConstraints().apply {
            gridx   = 0
            weightx = 1.0
            fill    = GridBagConstraints.HORIZONTAL
            anchor  = GridBagConstraints.NORTHWEST
        }

        settingsModel.schema.forEachIndexed { index, setting ->
            val comp = buildComponent(setting)
            components[setting.propertyName] = comp

            // Label: single JLabel with HTML for the required asterisk — no nested panels
            val requiredSuffix = if (setting.isRequired) " <font color='red'>*</font>" else ""
            val label = JLabel("<html><b>${setting.label}$requiredSuffix</b></html>").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }

            // Ensure the input fills the full width and stays left-aligned
            comp.component.apply {
                alignmentX  = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(28))
            }

            // Build the block: every child must have alignmentX = LEFT_ALIGNMENT
            // so BoxLayout keeps them in a single column with no horizontal drift.
            val block = JPanel().apply {
                layout    = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque  = false
                alignmentX = Component.LEFT_ALIGNMENT

                add(label)
                add(Box.createVerticalStrut(4))
                add(comp.component)

                if (setting.description.isNotBlank()) {
                    add(Box.createVerticalStrut(3))
                    add(JLabel("<html><i>${setting.description}</i></html>").apply {
                        foreground = UIManager.getColor("Label.disabledForeground")
                        font       = font.deriveFont(font.size - 1f)
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                }
            }

            rowConstraints.gridy  = index
            rowConstraints.insets = Insets(if (index == 0) 0 else 14, 0, 0, 0)
            formPanel.add(block, rowConstraints.clone())
        }

        // Bottom glue
        val glueConstraints = GridBagConstraints().apply {
            gridx     = 0; gridy = settingsModel.schema.size
            weightx   = 1.0; weighty = 1.0
            fill      = GridBagConstraints.BOTH
        }
        formPanel.add(Box.createVerticalGlue(), glueConstraints)

        // ---- Scroll wrapper ----
        val scroll = JScrollPane(formPanel).apply {
            border = null
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            // Ensure the viewport width drives how wide components expand
            viewport.isOpaque = false
        }

        // ---- Button bar ----
        val buttonBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
            border = BorderFactory.createMatteBorder(
                1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY
            )
            add(JButton(localizationManager.getString("common.save")).apply   { addActionListener { onSaveClicked() }; isDefaultCapable = true })
            add(JButton(localizationManager.getString("common.cancel")).apply { addActionListener { dispose() } })
        }

        add(scroll,    BorderLayout.CENTER)
        add(buttonBar, BorderLayout.SOUTH)

        // Register Enter → Save, Escape → Cancel
        rootPane.defaultButton = buttonBar.components
            .filterIsInstance<JButton>().firstOrNull { it.text == "Save" }
        rootPane.registerKeyboardAction(
            { dispose() },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize   = Dimension(520, 320)
        preferredSize = Dimension(620, 480)
        pack()
        setLocationRelativeTo(owner)
    }

    // -------------------------------------------------------------------------
    // Component factory
    // -------------------------------------------------------------------------

    private fun buildComponent(setting: SettingSchema): SettingComponent = when (setting) {
        is TextSetting -> {
            // Use columns=0 and let the layout manager size it — never use a fixed column count
            val f = JTextField(setting.currentValue)
            SettingComponent(f) { f.text }
        }
        is PasswordSetting -> {
            val f = JPasswordField(setting.currentValue)
            SettingComponent(f) { String(f.password) }
        }
        is TextAreaSetting -> {
            val area = JTextArea(setting.currentValue, setting.rows, 0).apply {
                lineWrap = true; wrapStyleWord = true
            }
            // Give the scroll pane a sensible preferred height but let width fill
            val scrollArea = JScrollPane(area).apply {
                preferredSize = Dimension(0, (setting.rows * 20).coerceAtLeast(80))
                maximumSize   = Dimension(Int.MAX_VALUE, (setting.rows * 20).coerceAtLeast(80))
            }
            SettingComponent(scrollArea) { area.text }
        }
        is IntegerSetting -> {
            val sp = JSpinner(SpinnerNumberModel(
                setting.currentValue.toIntOrNull() ?: 0,
                setting.minValue ?: Int.MIN_VALUE,
                setting.maxValue ?: Int.MAX_VALUE,
                setting.step     ?: 1
            ))
            SettingComponent(sp) { sp.value.toString() }
        }
        is NumberSetting -> {
            val sp = JSpinner(SpinnerNumberModel(
                setting.currentValue.toDoubleOrNull() ?: 0.0,
                setting.minValue ?: Double.MIN_VALUE,
                setting.maxValue ?: Double.MAX_VALUE,
                setting.step     ?: 1.0
            ))
            SettingComponent(sp) { sp.value.toString() }
        }
        is BooleanSetting -> {
            val cb = JCheckBox("", setting.currentValue.toBooleanStrictOrNull() ?: false)
            SettingComponent(cb) { cb.isSelected.toString() }
        }
        is DropdownSetting -> {
            val cb = JComboBox(setting.options.toTypedArray()).apply {
                selectedItem = setting.currentValue
                // Remove the GridBag normalizer's fixed width — let it fill naturally
                preferredSize = null
                maximumSize   = Dimension(Int.MAX_VALUE, preferredSize?.height ?: 28)
            }
            SettingComponent(cb) { cb.selectedItem?.toString() ?: "" }
        }
        is FilePathSetting, is DirectoryPathSetting -> {
            val isDir = setting is DirectoryPathSetting
            val tf    = JTextField(setting.currentValue)
            val btn   = JButton(localizationManager.getString("common.browse")).apply {
                addActionListener {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = if (isDir) JFileChooser.DIRECTORIES_ONLY
                        else       JFileChooser.FILES_ONLY
                        if (!isDir && setting is FilePathSetting && setting.fileExtensions.isNotEmpty())
                            fileFilter = FileNameExtensionFilter(
                                setting.fileExtensions.joinToString(", "),
                                *setting.fileExtensions.toTypedArray()
                            )
                        if (tf.text.isNotBlank()) selectedFile = File(tf.text)
                    }
                    if (chooser.showOpenDialog(this@DynamicPluginSettingsDialog) == JFileChooser.APPROVE_OPTION)
                        tf.text = chooser.selectedFile.absolutePath
                }
            }
            // Row panel: text field expands, Browse button stays fixed width
            val row = JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(tf,  BorderLayout.CENTER)
                add(btn, BorderLayout.EAST)
            }
            SettingComponent(row) { tf.text }
        }
    }

    // -------------------------------------------------------------------------
    // Save + validation
    // -------------------------------------------------------------------------

    private fun onSaveClicked() {
        val values = components.mapValues { it.value.getValue() }

        // Reset all highlights first
        components.values.forEach { clearHighlight(it.component) }

        val missing = settingsModel.schema.filter { s ->
            s.isRequired && values[s.propertyName].isNullOrBlank()
        }

        if (missing.isNotEmpty()) {
            missing.forEach { s -> applyErrorHighlight(components[s.propertyName]?.component) }

            // Focus the first invalid field
            components[missing.first().propertyName]?.component?.let { comp ->
                val focusTarget = firstFocusable(comp)
                focusTarget?.requestFocusInWindow()
            }

            JOptionPane.showMessageDialog(
                this,
                "<html>${localizationManager.getString("plugin_config.required_fields_error_msg")}<br>• ${
                    missing.joinToString("<br>• ") { it.label }
                }</html>",
                localizationManager.getString("plugin_config.required_fields_error_title"),
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        onSave(values)
        dispose()
    }

    private fun applyErrorHighlight(comp: JComponent?) {
        val target = firstInputComponent(comp) ?: return
        target.border = BorderFactory.createLineBorder(
            UIManager.getColor("Actions.Red") ?: Color.RED, 2
        )
    }

    private fun clearHighlight(comp: JComponent?) {
        val target = firstInputComponent(comp) ?: return
        target.border = UIManager.getBorder("TextField.border")
            ?: BorderFactory.createEmptyBorder(2, 4, 2, 4)
    }

    /** Unwraps wrapper panels to find the first real input component. */
    private fun firstInputComponent(comp: JComponent?): JComponent? = when {
        comp == null           -> null
        comp is JTextField     -> comp
        comp is JPasswordField -> comp
        comp is JSpinner       -> comp
        comp is JCheckBox      -> comp
        comp is JComboBox<*>   -> comp
        comp is JScrollPane    -> comp
        comp is JPanel         -> comp.components.filterIsInstance<JComponent>().firstOrNull()
        else                   -> comp
    }

    private fun firstFocusable(comp: JComponent): java.awt.Component? =
        if (comp.isFocusable) comp
        else comp.components.filterIsInstance<java.awt.Component>().firstOrNull { it.isFocusable }

    private data class SettingComponent(val component: JComponent, val getValue: () -> String)
}
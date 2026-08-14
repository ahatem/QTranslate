package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.plugin.ServiceValidation
import com.github.ahatem.qtranslate.core.plugin.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.event.ItemEvent
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.text.JTextComponent
import kotlin.math.roundToInt

/**
 * A dialog that builds its form dynamically from a [PluginSettingsModel] schema.
 *
 * ### Layout
 * Fields are organized into named sections ([PluginSettingsGroup]). Each group renders
 * a bold section header (if titled), an optional action button strip, and its fields.
 * Plugins without `@SettingGroup` annotations render as a flat list with no headers.
 *
 * ### Features
 * - **Inline validation** — red border + character counter update as the user types;
 *   Save is disabled while any field is invalid.
 * - **Conditional visibility** — fields with `showIf` rules are hidden/shown in real time
 *   as the controlling field's value changes.
 * - **Slider** — [SliderSetting] renders a horizontal JSlider with a live value label.
 * - **Action buttons** — [PluginActionSchema] actions are rendered as buttons that invoke
 *   methods on the live settings instance via reflection.
 * - **Text wrapping** — description/hint text uses a non-editable JTextArea so long
 *   strings wrap naturally when the dialog is resized.
 */
class DynamicPluginSettingsDialog(
    owner: Window?,
    pluginName: String,
    private val localizationManager: LocalizationManager,
    private val settingsModel: PluginSettingsModel,
    /** Live settings instance for @PluginAction invocation. May be null for plugins without actions. */
    private val settingsInstance: PluginSettings.Configurable? = null,
    private val onSave: (Map<String, String>) -> Unit,
    /**
     * Applies the values on screen and then asks each service whether it works, reporting one
     * line per service.
     *
     * Null for a plugin with nothing to check, which hides the button. Testing applies first
     * because otherwise the user would be testing the key they saved last time rather than the
     * one they just typed.
     */
    private val onTestConnection: (suspend (Map<String, String>) -> List<ServiceValidation>)? = null
) : JDialog(
    owner,
    localizationManager.getString("plugin_config.title_format").format(pluginName),
    ModalityType.APPLICATION_MODAL
) {

    /** Holds the input component + its outer block (for showIf toggling) + value getter. */
    private data class SettingComponent(
        val fieldBlock: JPanel,
        val component: JComponent,
        val getValue: () -> String
    )

    private val components = mutableMapOf<String, SettingComponent>()
    private val saveButton = JButton(localizationManager.getString("common.save"))
    private val testButton = JButton(localizationManager.getString("plugin_config.test_connection")).apply {
        addActionListener { onTestClicked() }
    }
    private val testStatusLabel = JLabel().apply {
        isVisible = false
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
        font = font.deriveFont(font.size - 0.5f)
    }

    /** Cancelled with the dialog, so a slow check cannot outlive the window it reports into. */
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var rowIndex = 0
    private var buttonBar: JPanel? = null

    init {
        layout = BorderLayout()

        val formPanel = buildScrollableFormPanel()

        settingsModel.groups.forEach { group ->
            renderGroup(group, formPanel)
        }

        // Bottom glue so content stays at the top
        val glueConstraints = GridBagConstraints().apply {
            gridx = 0; gridy = rowIndex
            weightx = 1.0; weighty = 1.0
            fill = GridBagConstraints.BOTH
        }
        formPanel.add(Box.createVerticalGlue(), glueConstraints)

        val scroll = JScrollPane(formPanel).apply {
            clearBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            viewport.isOpaque = false
        }

        buttonBar = buildButtonBar()

        add(scroll, BorderLayout.CENTER)
        add(buttonBar, BorderLayout.SOUTH)

        // Keep button-bar top-border in sync with the active theme
        UIManager.addPropertyChangeListener { evt ->
            if (evt.propertyName == "lookAndFeel") SwingUtilities.invokeLater { updateButtonBarBorder() }
        }

        // Keyboard shortcuts
        rootPane.defaultButton = saveButton
        rootPane.registerKeyboardAction(
            { dispose() },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        // Apply initial showIf visibility
        SwingUtilities.invokeLater { updateVisibility() }

        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(520, 320)
        preferredSize = Dimension(UIScale.scale(640), UIScale.scale(500))
        pack()
        setLocationRelativeTo(owner)
    }

    // =========================================================================
    // Form panel (scrollable, width-tracked)
    // =========================================================================

    private fun buildScrollableFormPanel(): JPanel {
        val panel = object : JPanel(GridBagLayout()), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
            override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = 64
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply {
            border = BorderFactory.createEmptyBorder(16, 16, 8, 16)
        }
        return panel
    }

    // =========================================================================
    // Group rendering
    // =========================================================================

    private fun renderGroup(group: PluginSettingsGroup, panel: JPanel) {
        // Section header (only for named groups)
        if (group.title.isNotBlank()) {
            addToPanel(panel, buildGroupHeader(group))
        }

        // Action button strip (standalone @PluginAction buttons)
        if (group.actions.isNotEmpty()) {
            addToPanel(panel, buildActionStrip(group.actions), topInset = if (group.title.isNotBlank()) 4 else 0)
        }

        // Fields — detect consecutive "compact" pairs for side-by-side layout
        val fields = group.fields
        var i = 0
        while (i < fields.size) {
            val current = fields[i]
            val next = fields.getOrNull(i + 1)

            if (isCompact(current) && next != null && isCompact(next)) {
                // Place two compact fields side-by-side
                addToPanel(panel, buildSideBySideRow(current, next))
                i += 2
            } else {
                addToPanel(panel, buildFieldBlock(current))
                i++
            }
        }
    }

    /** Compact fields can be placed side-by-side to save vertical space. */
    private fun isCompact(s: SettingSchema) = s is BooleanSetting || s is SliderSetting

    private fun addToPanel(
        panel: JPanel,
        component: JComponent,
        topInset: Int = -1
    ) {
        val inset = if (topInset >= 0) topInset else if (rowIndex == 0) 0 else 14
        val c = GridBagConstraints().apply {
            gridx = 0; gridy = rowIndex
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.FIRST_LINE_START
            insets = Insets(inset, 0, 0, 0)
        }
        panel.add(component, c)
        rowIndex++
    }

    // =========================================================================
    // Group header — same visual pattern as SettingsPanel.addSeparator
    // =========================================================================

    /**
     * Renders a bold title + extending horizontal separator line, painted at the
     * vertical center of the label in the active theme color. Mirrors the logic
     * in [SettingsPanel.buildSeparatorRow] so plugin settings feel visually
     * consistent with the rest of the settings dialog.
     */
    private fun buildGroupHeader(group: PluginSettingsGroup): JPanel {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        container.add(buildSeparatorRow(group.title, gap = 10))

        if (group.description.isNotBlank()) {
            container.add(Box.createVerticalStrut(4))
            container.add(buildHintArea(group.description))
        }

        return container
    }

    /**
     * A [FlowLayout] panel that draws a horizontal line from the end of [title]
     * to its right edge, at the exact vertical center of the title label — read
     * after layout so the position is pixel-perfect regardless of font or L&F.
     * Color is read at paint time, making it fully theme-aware.
     */
    private fun buildSeparatorRow(title: String, gap: Int = 10): JPanel {
        val label = JLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        return object : JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)) {
            init {
                isOpaque = false; add(label)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val centerY = label.y + label.height / 2
                val startX = label.x + label.width + gap
                if (startX >= width) return
                g.color = UIManager.getColor("Separator.foreground")
                    ?: UIManager.getColor("Component.borderColor")
                            ?: Color.GRAY
                g.drawLine(startX, centerY, width, centerY)
            }
        }.apply { alignmentX = Component.LEFT_ALIGNMENT }
    }

    // =========================================================================
    // Action button strip
    // =========================================================================

    private fun buildActionStrip(actions: List<PluginActionSchema>): JPanel {
        val strip = JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        actions.sortedBy { it.order }.forEach { action ->
            val btn = JButton(action.label).apply {
                if (action.tooltip.isNotBlank()) toolTipText = action.tooltip
                addActionListener { invokeAction(action, this) }
            }
            strip.add(btn)
        }
        return strip
    }

    private fun invokeAction(action: PluginActionSchema, sourceButton: JButton) {
        val instance = settingsInstance ?: return
        sourceButton.isEnabled = false
        object : SwingWorker<String?, Unit>() {
            override fun doInBackground(): String? = runCatching {
                val method = instance::class.java.getDeclaredMethod(action.methodName).apply {
                    isAccessible = true
                }
                method.invoke(instance) as? String
            }.getOrElse { e ->
                "Error: ${e.cause?.message ?: e.message}"
            }

            override fun done() {
                sourceButton.isEnabled = true
                val result = runCatching { get() }.getOrNull()
                if (result != null) {
                    JOptionPane.showMessageDialog(
                        this@DynamicPluginSettingsDialog,
                        result, action.label, JOptionPane.INFORMATION_MESSAGE
                    )
                }
            }
        }.execute()
    }

    // =========================================================================
    // Field block builders
    // =========================================================================

    private fun buildFieldBlock(setting: SettingSchema): JPanel {
        val comp = buildComponent(setting)
        components[setting.propertyName] = comp

        // GridBagLayout is more predictable than BoxLayout for form rows:
        // fill=HORIZONTAL gives each sub-row the full cell width at its natural
        // preferred height, with no maximumSize clamping needed.
        val block = JPanel(GridBagLayout()).apply { isOpaque = false }
        var subRow = 0
        fun addSubRow(c: JComponent, topInset: Int) {
            block.add(c, GridBagConstraints().apply {
                gridx = 0; gridy = subRow++
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.FIRST_LINE_START
                insets = Insets(topInset, 0, 0, 0)
            })
        }

        // Label row
        addSubRow(buildLabelRow(setting, comp), 0)

        // Input widget — every component gets fill=HORIZONTAL so it spans
        // the full width; preferred height comes from the component itself.
        addSubRow(comp.component, 4)

        // Character counter (only when maxLength is set)
        val maxLength = maxLengthOf(setting)
        if (maxLength > 0) addSubRow(buildCharCounter(comp, maxLength, setting), 2)

        // Description / hint
        if (setting.description.isNotBlank()) addSubRow(buildHintArea(setting.description), 3)

        // block itself is the showIf-toggle target
        components[setting.propertyName] = comp.copy(fieldBlock = block)
        return block
    }

    private fun buildLabelRow(setting: SettingSchema, comp: SettingComponent): JPanel {
        val row = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
        }

        val requiredSuffix = if (setting.isRequired) " <font color='red'>*</font>" else ""
        val label = JLabel("<html><b>${setting.label}$requiredSuffix</b></html>")
        row.add(label, BorderLayout.LINE_START)

        // Inline action button (from Setting.actionMethod)
        val actionMethod = actionMethodOf(setting)
        val actionLabel = actionLabelOf(setting)
        if (actionMethod.isNotBlank() && settingsInstance != null) {
            val actionSchema = PluginActionSchema(actionMethod, actionLabel.ifBlank { "Run" }, "", 0, "")
            val btn = JButton(actionSchema.label).apply {
                font = font.deriveFont(font.size - 1f)
                addActionListener { invokeAction(actionSchema, this) }
            }
            row.add(btn, BorderLayout.LINE_END)
        }

        return row
    }

    private fun buildSideBySideRow(a: SettingSchema, b: SettingSchema): JPanel {
        val row = JPanel(GridLayout(1, 2, 16, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        row.add(buildFieldBlock(a))
        row.add(buildFieldBlock(b))
        return row
    }

    // =========================================================================
    // Component factory  (one per SettingSchema subclass)
    // =========================================================================

    private fun buildComponent(setting: SettingSchema): SettingComponent {
        // Placeholder outer block — replaced after buildFieldBlock wraps it
        val placeholder = JPanel()

        return when (setting) {
            is TextSetting -> {
                val f = JTextField(setting.currentValue)
                attachTextValidation(f, setting)
                SettingComponent(placeholder, f) { f.text }
            }

            is PasswordSetting -> {
                val f = JPasswordField(setting.currentValue)
                attachTextValidation(f, setting)
                SettingComponent(placeholder, f) { String(f.password) }
            }

            is TextAreaSetting -> {
                val rows = setting.rows.coerceAtLeast(3)
                val area = JTextArea(setting.currentValue, rows, 0).apply {
                    lineWrap = true
                    wrapStyleWord = true
                }
                attachTextValidation(area, setting)
                val scroll = JScrollPane(area).apply {
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                }
                val fm = area.getFontMetrics(area.font)
                val targetH = (fm.height * rows + 8).coerceAtLeast(60)
                scroll.preferredSize = Dimension(UIScale.scale(300), targetH)
                scroll.minimumSize = Dimension(0, targetH)
                SettingComponent(placeholder, scroll) { area.text }
            }

            is IntegerSetting -> {
                val model = SpinnerNumberModel(
                    setting.currentValue.toIntOrNull() ?: 0,
                    setting.minValue ?: Int.MIN_VALUE,
                    setting.maxValue ?: Int.MAX_VALUE,
                    setting.step ?: 1
                )
                val sp = JSpinner(model)
                sp.addChangeListener { onAnyFieldChanged() }
                SettingComponent(placeholder, sp) { sp.value.toString() }
            }

            is NumberSetting -> {
                val model = SpinnerNumberModel(
                    setting.currentValue.toDoubleOrNull() ?: 0.0,
                    setting.minValue ?: Double.MIN_VALUE,
                    setting.maxValue ?: Double.MAX_VALUE,
                    setting.step ?: 1.0
                )
                val sp = JSpinner(model)
                sp.addChangeListener { onAnyFieldChanged() }
                SettingComponent(placeholder, sp) { sp.value.toString() }
            }

            is SliderSetting -> {
                val scale = if (setting.step > 0) (1.0 / setting.step).roundToInt().coerceAtLeast(1) else 100
                val intMin = (setting.minValue * scale).roundToInt()
                val intMax = (setting.maxValue * scale).roundToInt()
                val intVal = ((setting.currentValue.toDoubleOrNull() ?: setting.minValue) * scale)
                    .roundToInt().coerceIn(intMin, intMax)

                val slider = JSlider(intMin, intMax, intVal).apply {
                    paintTicks = false
                    paintLabels = false
                }
                val decimalPlaces = if (setting.step >= 1.0) 0 else {
                    setting.step.toString().substringAfter('.').trimEnd('0').length.coerceAtLeast(1)
                }
                val fmt = "%.${decimalPlaces}f"
                val valueLabel = JLabel(fmt.format(intVal.toDouble() / scale)).apply {
                    preferredSize = Dimension(UIScale.scale(48), preferredSize.height)
                    horizontalAlignment = SwingConstants.TRAILING
                    font = font.deriveFont(Font.BOLD)
                }
                slider.addChangeListener {
                    valueLabel.text = fmt.format(slider.value.toDouble() / scale)
                    onAnyFieldChanged()
                }
                val row = JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                    add(slider, BorderLayout.CENTER)
                    add(valueLabel, BorderLayout.LINE_END)
                }
                SettingComponent(placeholder, row) { (slider.value.toDouble() / scale).toString() }
            }

            is BooleanSetting -> {
                val cb = JCheckBox("", setting.currentValue.toBooleanStrictOrNull() ?: false)
                cb.addItemListener { onAnyFieldChanged() }
                SettingComponent(placeholder, cb) { cb.isSelected.toString() }
            }

            is DropdownSetting -> {
                val cb = JComboBox(setting.options.toTypedArray()).apply {
                    selectedItem = setting.currentValue
                    preferredSize = null
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize?.height ?: 28)
                    // Limit display width so combo doesn't bloat the dialog
                    prototypeDisplayValue = "X".repeat(35)
                }
                cb.addItemListener { e ->
                    if (e.stateChange == ItemEvent.SELECTED) onAnyFieldChanged()
                }
                SettingComponent(placeholder, cb) { cb.selectedItem?.toString() ?: "" }
            }

            is FilePathSetting, is DirectoryPathSetting -> {
                val isDir = setting is DirectoryPathSetting
                val tf = JTextField(setting.currentValue)
                attachTextValidation(tf, setting)
                val btn = JButton(localizationManager.getString("common.browse")).apply {
                    addActionListener {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = if (isDir) JFileChooser.DIRECTORIES_ONLY
                            else JFileChooser.FILES_ONLY
                            if (!isDir && setting is FilePathSetting) {
                                isMultiSelectionEnabled = setting.allowMultiple
                                if (setting.fileExtensions.isNotEmpty())
                                    fileFilter = FileNameExtensionFilter(
                                        setting.fileExtensions.joinToString(", "),
                                        *setting.fileExtensions.toTypedArray()
                                    )
                            }
                            if (tf.text.isNotBlank()) selectedFile = File(tf.text)
                        }
                        if (chooser.showOpenDialog(this@DynamicPluginSettingsDialog) == JFileChooser.APPROVE_OPTION) {
                            tf.text = if (!isDir && setting is FilePathSetting && setting.allowMultiple)
                                chooser.selectedFiles.joinToString(";") { it.absolutePath }
                            else
                                chooser.selectedFile.absolutePath
                        }
                    }
                }
                val row = JPanel(BorderLayout(6, 0)).apply {
                    isOpaque = false
                    add(tf, BorderLayout.CENTER)
                    add(btn, BorderLayout.LINE_END)
                }
                SettingComponent(placeholder, row) { tf.text }
            }

            is CustomPanelSetting -> {
                val customComponent = runCatching {
                    val instance = settingsInstance
                        ?: error("settingsInstance required for CUSTOM_PANEL '${setting.factoryMethod}'")
                    val method = instance::class.java.getDeclaredMethod(setting.factoryMethod)
                        .apply { isAccessible = true }
                    method.invoke(instance) as? JComponent
                        ?: error("Factory method '${setting.factoryMethod}' returned null or non-JComponent")
                }.getOrElse { e ->
                    JLabel("<html><i>Custom panel unavailable: ${e.message}</i></html>").apply {
                        foreground = UIManager.getColor("Actions.Red") ?: Color.RED
                    }
                }
                SettingComponent(placeholder, customComponent) { "" }
            }
        }
    }

    // =========================================================================
    // Inline validation
    // =========================================================================

    private fun attachTextValidation(comp: JTextComponent, setting: SettingSchema) {
        comp.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onAnyFieldChanged()
            override fun removeUpdate(e: DocumentEvent?) = onAnyFieldChanged()
            override fun changedUpdate(e: DocumentEvent?) = onAnyFieldChanged()
        })
    }

    private fun onAnyFieldChanged() {
        updateVisibility()
        revalidateAll()
    }

    private fun updateVisibility() {
        val currentValues = components.mapValues { it.value.getValue() }
        settingsModel.schema.forEach { setting ->
            val rule = setting.showIf ?: return@forEach
            val visible = currentValues[rule.propertyName] == rule.requiredValue
            components[setting.propertyName]?.fieldBlock?.isVisible = visible
        }
        // Force layout update
        components.values.firstOrNull()?.fieldBlock?.parent?.let { parent ->
            parent.revalidate()
            parent.repaint()
        }
    }

    private fun revalidateAll() {
        var allValid = true
        settingsModel.schema.forEach { setting ->
            val comp = components[setting.propertyName] ?: return@forEach
            val value = comp.getValue()
            val valid = validateField(value, setting)
            if (!valid) allValid = false
            applyValidationHighlight(comp.component, valid)
        }
        saveButton.isEnabled = allValid
    }

    private fun validateField(value: String, setting: SettingSchema): Boolean {
        if (setting.isRequired && value.isBlank()) return false
        val max = maxLengthOf(setting)
        if (max > 0 && value.length > max) return false
        if (setting is TextSetting && setting.validation.isNotBlank())
            if (!Regex(setting.validation).matches(value)) return false
        if (setting is PasswordSetting && setting.validation.isNotBlank())
            if (!Regex(setting.validation).matches(value)) return false
        return true
    }

    private fun applyValidationHighlight(comp: JComponent, valid: Boolean) {
        val target = firstInputComponent(comp) ?: return
        target.border = if (valid)
            UIManager.getBorder("TextField.border") ?: BorderFactory.createEmptyBorder(2, 4, 2, 4)
        else
            BorderFactory.createLineBorder(UIManager.getColor("Actions.Red") ?: Color.RED, 2)
    }

    // =========================================================================
    // Character counter
    // =========================================================================

    private fun buildCharCounter(
        comp: SettingComponent,
        maxLength: Int,
        setting: SettingSchema
    ): JLabel {
        val label = JLabel("0 / $maxLength").apply {
            font = font.deriveFont(font.size - 1f)
            foreground = UIManager.getColor("Label.disabledForeground")
            alignmentX = Component.LEFT_ALIGNMENT
        }

        fun update() {
            val len = comp.getValue().length
            label.text = "$len / $maxLength"
            label.foreground = if (len > maxLength)
                UIManager.getColor("Actions.Red") ?: Color.RED
            else
                UIManager.getColor("Label.disabledForeground")
        }
        // Wire to the underlying text component
        val inputComp = firstInputComponent(comp.component)
        if (inputComp is JTextComponent) {
            inputComp.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = update()
                override fun removeUpdate(e: DocumentEvent?) = update()
                override fun changedUpdate(e: DocumentEvent?) = update()
            })
        }
        update()
        return label
    }

    // =========================================================================
    // Hint label (wraps long strings via HTML, styled like a muted label)
    // =========================================================================

    private fun buildHintArea(text: String): JLabel {
        // Escape HTML special characters so raw description text renders correctly.
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return JLabel("<html>$escaped</html>").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
            alignmentX = Component.LEFT_ALIGNMENT
            // HTML JLabels reflow text when the layout manager sets their width,
            // so they wrap correctly on dialog resize without collapsing to zero.
        }
    }

    // =========================================================================
    // Save + validation
    // =========================================================================

    private fun buildButtonBar(): JPanel {
        saveButton.addActionListener { onSaveClicked() }
        saveButton.isDefaultCapable = true

        val cancelButton = JButton(localizationManager.getString("common.cancel")).apply {
            addActionListener { dispose() }
        }

        return JPanel(BorderLayout()).apply {
            // The test result sits on the left, where it can grow without pushing the buttons
            // around, and the buttons stay where they were.
            add(testStatusLabel, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.TRAILING, 8, 8)).apply {
                    isOpaque = false
                    if (onTestConnection != null) add(testButton)
                    add(saveButton)
                    add(cancelButton)
                },
                BorderLayout.LINE_END
            )
        }.also { updateButtonBarBorder(it) }
    }

    /**
     * Runs the plugin's own checks and reports them.
     *
     * The dialog stays open on both outcomes: a failure is something to correct here, and closing
     * would take the field being corrected away with it.
     */
    private fun onTestClicked() {
        val test = onTestConnection ?: return
        val values = components.mapValues { it.value.getValue() }

        testButton.isEnabled = false
        showTestStatus(localizationManager.getString("plugin_config.testing"), null)

        dialogScope.launch {
            val results = runCatching { test(values) }.getOrElse { thrown ->
                listOf(
                    ServiceValidation(
                        serviceId = "",
                        serviceName = "",
                        error = ServiceError.UnknownError(thrown.message ?: thrown::class.java.simpleName, thrown)
                    )
                )
            }

            withContext(Dispatchers.Swing) {
                testButton.isEnabled = true
                reportTestResults(results)
            }
        }
    }

    private fun reportTestResults(results: List<ServiceValidation>) {
        if (results.isEmpty()) {
            showTestStatus(localizationManager.getString("plugin_config.test_nothing_to_check"), null)
            return
        }

        val failed = results.filterNot { it.isReady }
        if (failed.isEmpty()) {
            showTestStatus(
                localizationManager.getString("plugin_config.test_ok").format(results.size),
                UIManager.getColor("Actions.Green")
            )
            return
        }

        // The first failure inline, the rest in a dialog: one bad key is the common case and
        // deserves to be readable without a second click.
        showTestStatus(
            failed.first().error?.message ?: localizationManager.getString("plugin_config.test_failed"),
            UIManager.getColor("Actions.Red") ?: Color.RED
        )
        if (failed.size > 1) {
            JOptionPane.showMessageDialog(
                this,
                "<html>${failed.joinToString("<br>") { "• <b>${it.serviceName}</b>: ${it.error?.message.orEmpty()}" }}</html>",
                localizationManager.getString("plugin_config.test_failed"),
                JOptionPane.WARNING_MESSAGE
            )
        }
    }

    private fun showTestStatus(message: String, color: Color?) {
        testStatusLabel.text = message
        testStatusLabel.foreground = color ?: UIManager.getColor("Label.disabledForeground")
        testStatusLabel.isVisible = message.isNotBlank()
    }

    override fun dispose() {
        // A check can still be in flight; without this it would come back to a disposed window.
        dialogScope.cancel()
        super.dispose()
    }

    private fun updateButtonBarBorder(bar: JPanel? = buttonBar) {
        bar?.border = BorderFactory.createMatteBorder(
            1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY
        )
    }

    private fun onSaveClicked() {
        val values = components.mapValues { it.value.getValue() }
        val missing = settingsModel.schema.filter { s ->
            s.isRequired && values[s.propertyName].isNullOrBlank()
        }

        if (missing.isNotEmpty()) {
            missing.forEach { s -> applyErrorHighlight(components[s.propertyName]?.component) }
            components[missing.first().propertyName]?.component?.let { comp ->
                firstFocusable(comp)?.requestFocusInWindow()
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

    // =========================================================================
    // Utility helpers
    // =========================================================================

    private fun maxLengthOf(setting: SettingSchema) = when (setting) {
        is TextSetting -> setting.maxLength
        is PasswordSetting -> setting.maxLength
        is TextAreaSetting -> setting.maxLength
        else -> 0
    }

    // The schema types don't carry actionMethod/actionLabel directly
    // (those are consumed by the builder to produce CustomPanelSetting or to attach buttons).
    // For inline action buttons per-field, we rely on the SettingArgs.actionMethod/actionLabel
    // having been stored; since the current schema sealed classes don't expose them,
    // this lookup always returns empty — @PluginAction standalone actions handle the use case.
    private fun actionMethodOf(setting: SettingSchema): String = ""
    private fun actionLabelOf(setting: SettingSchema): String = ""

    private fun firstInputComponent(comp: JComponent?): JComponent? = when {
        comp == null -> null
        comp is JTextField -> comp
        comp is JPasswordField -> comp
        comp is JSpinner -> comp
        comp is JCheckBox -> comp
        comp is JComboBox<*> -> comp
        comp is JScrollPane -> comp
        comp is JSlider -> comp
        comp is JPanel -> comp.components.filterIsInstance<JComponent>().firstOrNull { it !is JLabel }
        else -> comp
    }

    private fun firstFocusable(comp: JComponent): Component? =
        if (comp.isFocusable) comp
        else comp.components.filterIsInstance<Component>().firstOrNull { it.isFocusable }
}

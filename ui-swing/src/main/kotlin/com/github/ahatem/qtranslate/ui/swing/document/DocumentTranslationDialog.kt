package com.github.ahatem.qtranslate.ui.swing.document

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.document.DocumentFormat
import com.github.ahatem.qtranslate.core.document.DocumentTranslationProgress
import com.github.ahatem.qtranslate.core.document.PdfTranslationMode
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Insets
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.MatteBorder
import javax.swing.filechooser.FileNameExtensionFilter

data class DocumentTranslationStrings(
    val title: String,
    val inputFile: String,
    val outputFile: String,
    val browse: String,
    val translate: String,
    val open: String,
    val openFailed: String,
    val cancel: String,
    val close: String,
    val ready: String,
    val pdfMode: String,
    val layoutAware: String,
    val layoutAwareDescription: String,
    val textOnly: String,
    val textOnlyDescription: String,
    val chooseInput: String,
    val chooseOutput: String,
    val preparing: String,
    val translating: String,
    val completed: String,
    val cancelled: String
)

class DocumentTranslationDialog(
    owner: Window,
    private val iconManager: IconManager,
    private val strings: DocumentTranslationStrings,
    private val onStart: (File, File, PdfTranslationMode) -> Unit,
    private val onCancel: () -> Unit
) : JDialog(owner, strings.title, ModalityType.MODELESS) {
    private enum class ViewState { READY, TRANSLATING, COMPLETED, FAILED, CANCELLED }

    private val inputField = filePathField(strings.chooseInput)
    private val outputField = filePathField(strings.chooseOutput)
    private val inputButton = filePickerButton(strings.chooseInput)
    private val outputButton = filePickerButton(strings.chooseOutput).apply { isEnabled = false }
    private val pdfModeCombo = JComboBox(arrayOf(strings.layoutAware, strings.textOnly))
    private val pdfDescription = secondaryLabel(strings.layoutAwareDescription)
    private val pdfOptionsPanel = createPdfOptionsPanel().apply { isVisible = false }
    private val progressBar = JProgressBar(0, 100).apply {
        value = 0
        preferredSize = Dimension(100, 6)
    }
    private val statusLabel = JLabel(strings.ready)
    private val progressLabel = JLabel("0%").apply { horizontalAlignment = SwingConstants.TRAILING }
    private val primaryButton = JButton(strings.translate).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "default")
        isEnabled = false
    }
    private val cancelButton = JButton(strings.close)
    private val contentPanel = createContentPanel()
    private val actionBar = createActionBar()
    private val lookAndFeelListener = PropertyChangeListener { event ->
        if (event.propertyName == "lookAndFeel") SwingUtilities.invokeLater(::updateTheme)
    }

    private var inputFile: File? = null
    private var outputFile: File? = null
    private var viewState = ViewState.READY
    private var progressVisible = false

    init {
        com.github.ahatem.qtranslate.ui.swing.shared.util.AppIcons.applyTo(this)
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        isResizable = false
        contentPane = JPanel(BorderLayout()).apply {
            add(contentPanel, BorderLayout.CENTER)
            add(actionBar, BorderLayout.SOUTH)
        }
        rootPane.defaultButton = primaryButton
        rootPane.registerKeyboardAction(
            { cancelOrClose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        inputButton.addActionListener { chooseInput() }
        outputButton.addActionListener { chooseOutput() }
        pdfModeCombo.addActionListener {
            inputFile?.let(::updateSuggestedOutput)
            updatePdfDescription()
            if (inputFile != null) showState(ViewState.READY, strings.ready)
        }
        primaryButton.addActionListener {
            if (viewState == ViewState.COMPLETED) openOutput() else startTranslation()
        }
        cancelButton.addActionListener { cancelOrClose() }
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) = cancelOrClose()
        })
        UIManager.addPropertyChangeListener(lookAndFeelListener)

        updateTheme()
        pack()
        // Wide enough that a long file path is readable, and no wider. The floor used to be 600
        // against a minimum of 560, which held the window well past what its four rows need and
        // left it looking mostly empty.
        val packedWidth = width
        minimumSize = Dimension(UIScale.scale(MIN_DIALOG_WIDTH), height)
        size = Dimension(maxOf(packedWidth, UIScale.scale(MIN_DIALOG_WIDTH)), height)
        setLocationRelativeTo(owner)
    }

    fun open() {
        progressBar.value = 0
        progressLabel.text = "0%"
        showState(ViewState.READY, strings.ready)
        isVisible = true
        toFront()
    }

    fun updateProgress(progress: DocumentTranslationProgress) {
        progressBar.value = progress.percent
        progressLabel.text = "${progress.percent}%"
        showState(
            ViewState.TRANSLATING,
            strings.translating.format(progress.completedSegments, progress.totalSegments),
            progress.currentText.takeIf(String::isNotBlank)
        )
    }

    fun complete(output: File) {
        progressBar.value = 100
        progressLabel.text = "100%"
        showState(ViewState.COMPLETED, strings.completed.format(output.name), output.absolutePath)
    }

    fun translationContextChanged() {
        if (viewState == ViewState.COMPLETED || viewState == ViewState.FAILED || viewState == ViewState.CANCELLED) {
            showState(ViewState.READY, strings.ready)
        }
    }

    fun fail(message: String) {
        showState(ViewState.FAILED, message, message)
    }

    override fun dispose() {
        UIManager.removePropertyChangeListener(lookAndFeelListener)
        super.dispose()
    }

    // Spacing follows UISpacing, which is what the rest of the application lays out with. This
    // dialog is the only MigLayout in the app and had grown its own vocabulary — wider insets,
    // wider gaps — which is a large part of why it read as a separate tool rather than part of
    // QTranslate. MigLayout's units are scaled by FlatLaf, so these are logical pixels like
    // everywhere else.
    //
    // Nothing here forces a control's height. Every field and button used to be pinned to `h 32!`,
    // which is taller than the look and feel's own metrics and, being exact, ignored the user's
    // font size entirely: the rest of the app shrank with a smaller UI font and this dialog did
    // not.
    private fun createContentPanel() = JPanel(
        MigLayout("fillx, insets $PADDING, wrap 1, hidemode 3", "[grow,fill]", "")
    ).apply {
        add(fileSection(strings.inputFile, inputField, inputButton), "gapbottom $V_GAP")
        add(fileSection(strings.outputFile, outputField, outputButton), "gapbottom $V_GAP")
        add(pdfOptionsPanel, "gapbottom $V_GAP")
        add(createProgressPanel())
    }

    /**
     * A nested MigLayout panel that does not clip the focus ring of what it holds.
     *
     * FlatLaf paints focus *outside* a component's bounds, so a nested panel with zero insets
     * cuts the ring off along its own edges — visible on the file fields and the PDF picker
     * whenever they take focus. A two-pixel inset makes room, and `visualPadding` tells the
     * parent layout to disregard that room when aligning, so the extra space costs no shift.
     *
     * This is the FlatLaf author's own remedy for it, from JFormDesigner/FlatLaf#792, and it
     * holds only while the containing panel is also MigLayout — which is the case here.
     */
    private fun nestedPanel(layout: MigLayout) = JPanel(layout).apply {
        isOpaque = false
        putClientProperty(
            "visualPadding",
            UIScale.scale(Insets(FOCUS_INSET, FOCUS_INSET, FOCUS_INSET, FOCUS_INSET))
        )
    }

    private fun fileSection(
        title: String,
        pathField: JTextField,
        button: JButton
    ) = nestedPanel(
        MigLayout("insets $FOCUS_INSET, fillx", "[grow,fill]$LABEL_GAP[]", "[]$LABEL_GAP[]")
    ).apply {
        add(JLabel(title), "cell 0 0 2 1")
        add(pathField, "cell 0 1")
        add(button, "cell 1 1")
    }

    private fun createPdfOptionsPanel() = nestedPanel(
        MigLayout("insets $FOCUS_INSET, fillx, wrap 1", "[grow,fill]", "[]$LABEL_GAP[]$LABEL_GAP[]")
    ).apply {
        add(JLabel(strings.pdfMode))
        add(pdfModeCombo)
        add(pdfDescription)
    }

    private fun createProgressPanel() = nestedPanel(
        MigLayout("insets $FOCUS_INSET, fillx, hidemode 3", "[grow,fill][48!,right]", "[]$LABEL_GAP[]")
    ).apply {
        add(statusLabel, "cell 0 0")
        add(progressLabel, "cell 1 0")
        add(progressBar, "cell 0 1 2 1, growx")
    }

    /**
     * The two action buttons share a width through a size group rather than fixed pixel widths,
     * so they stay equal to one another while each still sizes to its own label — which matters
     * once the labels are translated and "Translate" becomes "Dokument übersetzen".
     */
    private fun createActionBar() = JPanel(MigLayout("insets $PADDING", "[grow][]$LABEL_GAP[]", "[]")).apply {
        add(cancelButton, "cell 1 0, sizegroup action")
        add(primaryButton, "cell 2 0, sizegroup action")
    }

    private fun startTranslation() {
        if (viewState == ViewState.TRANSLATING) return
        val input = inputFile
        val output = outputFile
        if (input == null) {
            showState(ViewState.FAILED, strings.chooseInput)
            return
        }
        if (output == null) {
            showState(ViewState.FAILED, strings.chooseOutput)
            return
        }
        progressBar.value = 0
        progressLabel.text = "0%"
        showState(ViewState.TRANSLATING, strings.preparing)
        onStart(input, output, selectedPdfMode())
    }

    private fun openOutput() {
        val output = outputFile ?: return
        runCatching {
            check(output.isFile) { "Output file does not exist." }
            check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                "Opening files is not supported on this system."
            }
            Desktop.getDesktop().open(output)
        }.onFailure { error ->
            JOptionPane.showMessageDialog(
                this,
                strings.openFailed.format(error.message ?: output.absolutePath),
                strings.title,
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun cancelOrClose() {
        if (viewState == ViewState.TRANSLATING) {
            onCancel()
            progressBar.value = 0
            progressLabel.text = "0%"
            showState(ViewState.CANCELLED, strings.cancelled)
        } else {
            isVisible = false
        }
    }

    /**
     * Opens the dialog with [file] already selected, for documents dropped onto the main
     * window. Callers are expected to have checked [DocumentFormat.from] first; an
     * unsupported file is ignored rather than opening an unusable dialog.
     */
    fun openWith(file: File) {
        if (DocumentFormat.from(file) == null) return
        applyInput(file)
        open()
    }

    private fun chooseInput() {
        val chooser = JFileChooser().apply {
            dialogTitle = strings.chooseInput
            fileFilter = FileNameExtensionFilter("DOCX, PDF, TXT, SRT, VTT", "docx", "pdf", "txt", "srt", "vtt")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        applyInput(chooser.selectedFile)
    }

    private fun applyInput(file: File) {
        inputFile = file
        inputField.text = file.absolutePath
        inputField.toolTipText = file.absolutePath
        pdfOptionsPanel.isVisible = DocumentFormat.from(file) == DocumentFormat.PDF
        updateSuggestedOutput(file)
        outputButton.isEnabled = true
        showState(ViewState.READY, strings.ready)
        resizeToContent()
    }

    private fun chooseOutput() {
        val input = inputFile ?: run {
            showState(ViewState.FAILED, strings.chooseInput)
            return
        }
        val extension = outputExtension(input)
        val chooser = JFileChooser(input.parentFile).apply {
            dialogTitle = strings.chooseOutput
            selectedFile = outputFile
            fileFilter = FileNameExtensionFilter(extension.uppercase(), extension)
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        setOutput(chooser.selectedFile.withExtension(extension))
        showState(ViewState.READY, strings.ready)
    }

    private fun updateSuggestedOutput(input: File) {
        val extension = outputExtension(input)
        setOutput(File(input.parentFile, "${input.nameWithoutExtension}.translated.$extension"))
    }

    private fun setOutput(file: File) {
        outputFile = file
        outputField.text = file.absolutePath
        outputField.toolTipText = file.absolutePath
    }

    private fun updatePdfDescription() {
        pdfDescription.text = when (selectedPdfMode()) {
            PdfTranslationMode.LAYOUT_AWARE -> strings.layoutAwareDescription
            PdfTranslationMode.TEXT_ONLY -> strings.textOnlyDescription
        }
    }

    private fun outputExtension(input: File): String = when {
        DocumentFormat.from(input) != DocumentFormat.PDF -> input.extension.lowercase()
        selectedPdfMode() == PdfTranslationMode.TEXT_ONLY -> "txt"
        else -> "pdf"
    }

    private fun selectedPdfMode(): PdfTranslationMode =
        if (pdfModeCombo.selectedIndex == 1) PdfTranslationMode.TEXT_ONLY else PdfTranslationMode.LAYOUT_AWARE

    private fun showState(state: ViewState, message: String, tooltip: String? = null) {
        viewState = state
        val running = state == ViewState.TRANSLATING
        inputButton.isEnabled = !running
        outputButton.isEnabled = !running && inputFile != null
        pdfModeCombo.isEnabled = !running
        primaryButton.isEnabled = !running && inputFile != null && outputFile != null
        primaryButton.text = if (state == ViewState.COMPLETED) strings.open else strings.translate
        primaryButton.icon = if (state == ViewState.COMPLETED) themeIcon("icons/lucide/book-open.svg") else null
        cancelButton.text = if (running) strings.cancel else strings.close
        statusLabel.text = message
        statusLabel.toolTipText = tooltip
        statusLabel.foreground = when (state) {
            ViewState.FAILED -> UIManager.getColor("Actions.Red") ?: Color(190, 45, 45)
            ViewState.COMPLETED -> UIManager.getColor("Actions.Green") ?: Color(35, 135, 70)
            else -> UIManager.getColor("Label.foreground")
        }
        val showProgress = state == ViewState.TRANSLATING || state == ViewState.COMPLETED
        progressBar.isVisible = showProgress
        progressLabel.isVisible = showProgress
        if (progressVisible != showProgress) {
            progressVisible = showProgress
            resizeToContent()
        }
    }

    private fun updateTheme() {
        val borderColor = UIManager.getColor("Component.borderColor") ?: Color.GRAY
        actionBar.border = MatteBorder(1, 0, 0, 0, borderColor)
        pdfDescription.foreground = UIManager.getColor("Label.disabledForeground")
        showState(viewState, statusLabel.text, statusLabel.toolTipText)
        revalidate()
        repaint()
    }

    private fun secondaryLabel(text: String = "") = JLabel(text).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
        putClientProperty(FlatClientProperties.STYLE, "font: -1")
    }

    // No minimum width override. This was the only button in the application asking for one, at
    // 96 against the look and feel's own 72, which made these read as a third larger than every
    // other button in QTranslate.
    private fun filePickerButton(tooltip: String) = JButton(
        strings.browse,
        themeIcon("icons/lucide/file-scan.svg")
    ).apply {
        toolTipText = tooltip
        accessibleContext.accessibleName = tooltip
    }

    private fun themeIcon(path: String) = FlatSVGIcon(path, 16, 16, javaClass.classLoader).apply {
        colorFilter = FlatSVGIcon.ColorFilter {
            UIManager.getColor("Button.foreground") ?: UIManager.getColor("Label.foreground") ?: Color.DARK_GRAY
        }
    }

    private fun filePathField(placeholder: String) = JTextField().apply {
        isEditable = false
        putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder)
        accessibleContext.accessibleName = placeholder
    }

    private fun resizeToContent() {
        val currentLocation = location
        pack()
        size = Dimension(maxOf(width, UIScale.scale(MIN_DIALOG_WIDTH)), height)
        location = currentLocation
    }

    private fun File.withExtension(extension: String): File =
        if (this.extension.equals(extension, ignoreCase = true)) this else File("$absolutePath.$extension")

    private companion object {
        /**
         * Layout spacing, in logical pixels.
         *
         * Matching `UISpacing`, which the rest of the application lays out with. Restated here as
         * plain numbers because MigLayout takes its constraints as strings and FlatLaf scales
         * them, so passing already-scaled values would scale them twice.
         */
        const val PADDING = 12
        const val V_GAP = 8

        /** The gap between a label and the control it labels, and between paired controls. */
        const val LABEL_GAP = 6

        /**
         * Room for FlatLaf's outer focus ring inside a nested panel.
         *
         * Two pixels is what the look and feel draws; see [nestedPanel] for why a nested panel
         * has to reserve it rather than letting the ring fall outside its bounds.
         */
        const val FOCUS_INSET = 2

        /** Wide enough for a readable file path, and no wider. */
        const val MIN_DIALOG_WIDTH = 520
    }
}

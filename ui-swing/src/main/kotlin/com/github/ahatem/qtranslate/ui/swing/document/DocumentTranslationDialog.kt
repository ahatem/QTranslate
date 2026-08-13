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
        val packedWidth = width
        minimumSize = Dimension(UIScale.scale(560), height)
        size = Dimension(maxOf(packedWidth, UIScale.scale(600)), height)
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

    private fun createContentPanel() = JPanel(
        MigLayout("fillx, insets 16, wrap 1, hidemode 3", "[grow,fill]", "")
    ).apply {
        add(fileSection(strings.inputFile, inputField, inputButton), "gapbottom 12")
        add(fileSection(strings.outputFile, outputField, outputButton), "gapbottom 12")
        add(pdfOptionsPanel, "gapbottom 12")
        add(createProgressPanel())
    }

    private fun fileSection(
        title: String,
        pathField: JTextField,
        button: JButton
    ) = JPanel(MigLayout("insets 0, fillx", "[grow,fill]8[]", "[]6[]")).apply {
        isOpaque = false
        add(JLabel(title), "cell 0 0 2 1")
        add(pathField, "cell 0 1, h 32!")
        add(button, "cell 1 1, h 32!")
    }

    private fun createPdfOptionsPanel() = JPanel(
        MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]6[]6[]")
    ).apply {
        isOpaque = false
        add(JLabel(strings.pdfMode))
        add(pdfModeCombo, "h 32!")
        add(pdfDescription)
    }

    private fun createProgressPanel() = JPanel(
        MigLayout("insets 0, fillx, hidemode 3", "[grow,fill][48!,right]", "[]7[]")
    ).apply {
        isOpaque = false
        add(statusLabel, "cell 0 0")
        add(progressLabel, "cell 1 0")
        add(progressBar, "cell 0 1 2 1, growx")
    }

    private fun createActionBar() = JPanel(MigLayout("insets 10", "[grow][]8[]", "[]")).apply {
        add(cancelButton, "cell 1 0, w 92!, h 32!")
        add(primaryButton, "cell 2 0, w 112!, h 32!")
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

    private fun chooseInput() {
        val chooser = JFileChooser().apply {
            dialogTitle = strings.chooseInput
            fileFilter = FileNameExtensionFilter("DOCX, PDF, TXT, SRT, VTT", "docx", "pdf", "txt", "srt", "vtt")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        inputFile = chooser.selectedFile
        inputField.text = chooser.selectedFile.absolutePath
        inputField.toolTipText = chooser.selectedFile.absolutePath
        pdfOptionsPanel.isVisible = DocumentFormat.from(chooser.selectedFile) == DocumentFormat.PDF
        updateSuggestedOutput(chooser.selectedFile)
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

    private fun filePickerButton(tooltip: String) = JButton(
        strings.browse,
        themeIcon("icons/lucide/file-scan.svg")
    ).apply {
        putClientProperty(FlatClientProperties.STYLE, "minimumWidth: 96")
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
        size = Dimension(maxOf(width, UIScale.scale(600)), height)
        location = currentLocation
    }

    private fun File.withExtension(extension: String): File =
        if (this.extension.equals(extension, ignoreCase = true)) this else File("$absolutePath.$extension")
}

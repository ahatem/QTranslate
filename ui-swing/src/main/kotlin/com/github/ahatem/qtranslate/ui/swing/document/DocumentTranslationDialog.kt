package com.github.ahatem.qtranslate.ui.swing.document

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.document.DocumentFormat
import com.github.ahatem.qtranslate.core.document.DocumentTranslationProgress
import com.github.ahatem.qtranslate.core.document.PdfTranslationMode
import net.miginfocom.swing.MigLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Window
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

data class DocumentTranslationStrings(
    val title: String,
    val subtitle: String,
    val inputFile: String,
    val outputFile: String,
    val browse: String,
    val translate: String,
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
    val errorTitle: String
)

class DocumentTranslationDialog(
    owner: Window,
    private val strings: DocumentTranslationStrings,
    private val onStart: (File, File, PdfTranslationMode) -> Unit,
    private val onCancel: () -> Unit
) : JDialog(owner, strings.title, ModalityType.MODELESS) {
    private val inputField = fileField()
    private val outputField = fileField()
    private val inputButton = filePickerButton(strings.chooseInput)
    private val outputButton = filePickerButton(strings.chooseOutput)
    private val layoutAwareButton = modeButton(strings.layoutAware, selected = true)
    private val textOnlyButton = modeButton(strings.textOnly)
    private val pdfDescription = JLabel(strings.layoutAwareDescription)
    private val pdfOptionsPanel = createPdfOptionsPanel()
    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = false
        value = 0
        preferredSize = Dimension(100, 8)
    }
    private val statusLabel = JLabel(strings.ready)
    private val progressLabel = JLabel("0%").apply { horizontalAlignment = SwingConstants.TRAILING }
    private val primaryButton = JButton(strings.translate).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "default")
    }
    private val cancelButton = JButton(strings.close)
    private var running = false

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(680, 480)
        preferredSize = Dimension(720, 500)
        isResizable = false
        contentPane = createContent()
        rootPane.defaultButton = primaryButton

        inputField.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, inputButton)
        outputField.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, outputButton)
        ButtonGroup().apply {
            add(layoutAwareButton)
            add(textOnlyButton)
        }

        inputButton.addActionListener { chooseInput() }
        outputButton.addActionListener { chooseOutput() }
        layoutAwareButton.addActionListener { selectPdfMode(PdfTranslationMode.LAYOUT_AWARE) }
        textOnlyButton.addActionListener { selectPdfMode(PdfTranslationMode.TEXT_ONLY) }
        primaryButton.addActionListener { startTranslation() }
        cancelButton.addActionListener {
            if (running) {
                onCancel()
                setRunning(false)
            } else {
                isVisible = false
            }
        }
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(event: java.awt.event.WindowEvent) {
                if (running) onCancel()
                setRunning(false)
                isVisible = false
            }
        })
        pack()
        setLocationRelativeTo(owner)
    }

    fun open() {
        setRunning(false)
        setStatus(strings.ready)
        progressBar.value = 0
        progressLabel.text = "0%"
        isVisible = true
        toFront()
    }

    fun updateProgress(progress: DocumentTranslationProgress) {
        if (!running) setRunning(true)
        progressBar.value = progress.percent
        progressLabel.text = "${progress.percent}%"
        statusLabel.text = strings.translating.format(progress.completedSegments, progress.totalSegments)
        statusLabel.toolTipText = progress.currentText.takeIf(String::isNotBlank)
    }

    fun complete(output: File) {
        setRunning(false)
        progressBar.value = 100
        progressLabel.text = "100%"
        setStatus(strings.completed.format(output.name))
        statusLabel.toolTipText = output.absolutePath
    }

    fun fail(message: String) {
        setRunning(false)
        setStatus(message, error = true)
        statusLabel.toolTipText = message
    }

    private fun createContent() = JPanel(
        MigLayout("fill, insets 24 28 20 28, wrap 1", "[grow,fill]", "[]20[]14[]14[]18[]push[]")
    ).apply {
        add(createHeader())
        add(labeledField(strings.inputFile, inputField))
        add(labeledField(strings.outputFile, outputField))
        add(pdfOptionsPanel)
        add(createProgressPanel())
        add(createActions(), "alignx right")
    }

    private fun createHeader() = JPanel(MigLayout("insets 0, fillx", "[]14[grow,fill]", "[]")).apply {
        isOpaque = false
        add(JLabel(themedIcon("icons/lucide/file-scan.svg", 34)))
        add(JPanel(MigLayout("insets 0, wrap 1", "[grow,fill]", "[]2[]")).apply {
            isOpaque = false
            add(JLabel(strings.title).apply {
                putClientProperty(FlatClientProperties.STYLE_CLASS, "h2")
            })
            add(JLabel(strings.subtitle).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
            })
        })
    }

    private fun labeledField(label: String, field: JTextField) =
        JPanel(MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]5[]")).apply {
            isOpaque = false
            add(JLabel(label))
            add(field, "h 38!")
        }

    private fun createPdfOptionsPanel() =
        JPanel(MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]5[]5[]")).apply {
            isOpaque = false
            isVisible = false
            add(JLabel(strings.pdfMode))
            add(JPanel(MigLayout("insets 0, gap 4", "[]0[]", "[]")).apply {
                isOpaque = false
                add(layoutAwareButton)
                add(textOnlyButton)
            })
            add(pdfDescription.apply { foreground = UIManager.getColor("Label.disabledForeground") })
        }

    private fun createProgressPanel() =
        JPanel(MigLayout("insets 0, fillx, wrap 2", "[grow,fill][60!,right]", "[]7[]")).apply {
            isOpaque = false
            add(statusLabel)
            add(progressLabel)
            add(progressBar, "span 2, growx")
        }

    private fun createActions() = JPanel(MigLayout("insets 0", "[]8[]", "[]")).apply {
        isOpaque = false
        add(cancelButton, "w 96!, h 34!")
        add(primaryButton, "w 120!, h 34!")
    }

    private fun startTranslation() {
        if (running) return
        val input = inputField.text.takeIf(String::isNotBlank)?.let(::File)
        val output = outputField.text.takeIf(String::isNotBlank)?.let(::File)
        if (input == null || output == null) {
            setStatus(strings.chooseInput, error = true)
            return
        }
        setRunning(true)
        setStatus(strings.preparing)
        onStart(input, output, selectedPdfMode())
    }

    private fun chooseInput() {
        val chooser = JFileChooser().apply {
            dialogTitle = strings.chooseInput
            fileFilter = FileNameExtensionFilter("DOCX, PDF, TXT, SRT, VTT", "docx", "pdf", "txt", "srt", "vtt")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val input = chooser.selectedFile
        inputField.text = input.absolutePath
        pdfOptionsPanel.isVisible = DocumentFormat.from(input) == DocumentFormat.PDF
        updateSuggestedOutput(input)
        setStatus(strings.ready)
    }

    private fun chooseOutput() {
        val input = inputField.text.takeIf(String::isNotBlank)?.let(::File)
        if (input == null) {
            setStatus(strings.chooseInput, error = true)
            return
        }
        val extension = outputExtension(input)
        val chooser = JFileChooser(input.parentFile).apply {
            dialogTitle = strings.chooseOutput
            selectedFile = File(outputField.text)
            fileFilter = FileNameExtensionFilter(extension.uppercase(), extension)
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        outputField.text = chooser.selectedFile.withExtension(extension).absolutePath
        setStatus(strings.ready)
    }

    private fun selectPdfMode(mode: PdfTranslationMode) {
        pdfDescription.text = when (mode) {
            PdfTranslationMode.LAYOUT_AWARE -> strings.layoutAwareDescription
            PdfTranslationMode.TEXT_ONLY -> strings.textOnlyDescription
        }
        inputField.text.takeIf(String::isNotBlank)?.let(::File)?.let(::updateSuggestedOutput)
    }

    private fun updateSuggestedOutput(input: File) {
        val extension = outputExtension(input)
        outputField.text = File(input.parentFile, "${input.nameWithoutExtension}.translated.$extension").absolutePath
    }

    private fun outputExtension(input: File): String = when {
        DocumentFormat.from(input) != DocumentFormat.PDF -> input.extension.lowercase()
        selectedPdfMode() == PdfTranslationMode.TEXT_ONLY -> "txt"
        else -> "docx"
    }

    private fun selectedPdfMode(): PdfTranslationMode =
        if (textOnlyButton.isSelected) PdfTranslationMode.TEXT_ONLY else PdfTranslationMode.LAYOUT_AWARE

    private fun setRunning(value: Boolean) {
        running = value
        inputField.isEnabled = !value
        outputField.isEnabled = !value
        inputButton.isEnabled = !value
        outputButton.isEnabled = !value
        layoutAwareButton.isEnabled = !value
        textOnlyButton.isEnabled = !value
        primaryButton.isEnabled = !value
        cancelButton.text = if (value) strings.cancel else strings.close
    }

    private fun setStatus(message: String, error: Boolean = false) {
        statusLabel.text = message
        statusLabel.foreground = if (error) {
            UIManager.getColor("Actions.Red") ?: Color(190, 45, 45)
        } else {
            UIManager.getColor("Label.foreground")
        }
        if (!error) statusLabel.toolTipText = null
    }

    private fun fileField() = JTextField().apply {
        isEditable = false
        putClientProperty(FlatClientProperties.STYLE, "arc: 8")
    }

    private fun filePickerButton(tooltip: String) = JButton(themedIcon("icons/lucide/file-scan.svg", 16)).apply {
        toolTipText = tooltip
        accessibleContext.accessibleName = tooltip
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
    }

    private fun modeButton(label: String, selected: Boolean = false) = JToggleButton(label, selected).apply {
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        putClientProperty(FlatClientProperties.STYLE, "arc: 8; margin: 6,14,6,14")
    }

    private fun themedIcon(path: String, size: Int) = FlatSVGIcon(path, size, size, javaClass.classLoader).apply {
        colorFilter = FlatSVGIcon.ColorFilter {
            UIManager.getColor("Label.foreground") ?: Color.DARK_GRAY
        }
    }

    private fun File.withExtension(extension: String): File =
        if (this.extension.equals(extension, ignoreCase = true)) this else File("$absolutePath.$extension")
}

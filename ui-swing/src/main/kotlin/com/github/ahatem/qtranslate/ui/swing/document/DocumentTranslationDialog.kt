package com.github.ahatem.qtranslate.ui.swing.document

import com.github.ahatem.qtranslate.core.document.DocumentFormat
import com.github.ahatem.qtranslate.core.document.DocumentTranslationProgress
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.io.File
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.filechooser.FileNameExtensionFilter

data class DocumentTranslationStrings(
    val title: String,
    val inputFile: String,
    val outputFile: String,
    val browse: String,
    val translate: String,
    val cancel: String,
    val close: String,
    val ready: String,
    val pdfNotice: String,
    val chooseInput: String,
    val chooseOutput: String,
    val completed: String,
    val errorTitle: String
)

class DocumentTranslationDialog(
    owner: Window,
    private val strings: DocumentTranslationStrings,
    private val onStart: (File, File) -> Unit,
    private val onCancel: () -> Unit
) : JDialog(owner, strings.title, ModalityType.MODELESS) {
    private val inputField = JTextField().apply { isEditable = false }
    private val outputField = JTextField().apply { isEditable = false }
    private val inputButton = JButton(strings.browse)
    private val outputButton = JButton(strings.browse)
    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        value = 0
    }
    private val statusLabel = JLabel(strings.ready, SwingConstants.LEADING)
    private val primaryButton = JButton(strings.translate)
    private val cancelButton = JButton(strings.cancel)
    private var running = false

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(560, 250)
        preferredSize = Dimension(620, 270)
        layout = BorderLayout(12, 12)

        val form = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(6, 8, 6, 8)
            fill = GridBagConstraints.HORIZONTAL
        }
        fun addRow(row: Int, label: String, field: JTextField, button: JButton) {
            constraints.gridy = row
            constraints.gridx = 0
            constraints.weightx = 0.0
            form.add(JLabel(label), constraints)
            constraints.gridx = 1
            constraints.weightx = 1.0
            form.add(field, constraints)
            constraints.gridx = 2
            constraints.weightx = 0.0
            form.add(button, constraints)
        }
        addRow(0, strings.inputFile, inputField, inputButton)
        addRow(1, strings.outputFile, outputField, outputButton)
        constraints.gridy = 2
        constraints.gridx = 0
        constraints.gridwidth = 3
        constraints.weightx = 1.0
        form.add(statusLabel, constraints)
        constraints.gridy = 3
        form.add(progressBar, constraints)
        add(form, BorderLayout.CENTER)

        add(JPanel(FlowLayout(FlowLayout.TRAILING)).apply {
            add(cancelButton)
            add(primaryButton)
        }, BorderLayout.SOUTH)

        inputButton.addActionListener { chooseInput() }
        outputButton.addActionListener { chooseOutput() }
        primaryButton.addActionListener {
            if (running) return@addActionListener
            val input = inputField.text.takeIf(String::isNotBlank)?.let(::File)
            val output = outputField.text.takeIf(String::isNotBlank)?.let(::File)
            if (input == null || output == null) {
                JOptionPane.showMessageDialog(this, strings.chooseInput, strings.errorTitle, JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }
            setRunning(true)
            onStart(input, output)
        }
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
        statusLabel.text = strings.ready
        progressBar.value = 0
        progressBar.string = "0%"
        isVisible = true
        toFront()
    }

    fun updateProgress(progress: DocumentTranslationProgress) {
        if (!running) setRunning(true)
        progressBar.value = progress.percent
        progressBar.string = "${progress.percent}% (${progress.completedSegments}/${progress.totalSegments})"
        statusLabel.text = progress.currentText.ifBlank { strings.ready }
    }

    fun complete(output: File) {
        setRunning(false)
        progressBar.value = 100
        progressBar.string = "100%"
        statusLabel.text = strings.completed.format(output.absolutePath)
    }

    fun fail(message: String) {
        setRunning(false)
        statusLabel.text = message
        JOptionPane.showMessageDialog(this, message, strings.errorTitle, JOptionPane.ERROR_MESSAGE)
    }

    private fun chooseInput() {
        val chooser = JFileChooser().apply {
            dialogTitle = strings.chooseInput
            fileFilter = FileNameExtensionFilter("DOCX, PDF, TXT, SRT, VTT", "docx", "pdf", "txt", "srt", "vtt")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val input = chooser.selectedFile
        inputField.text = input.absolutePath
        val format = DocumentFormat.from(input)
        val extension = if (format == DocumentFormat.PDF) "docx" else input.extension.lowercase()
        outputField.text = File(input.parentFile, "${input.nameWithoutExtension}.translated.$extension").absolutePath
        statusLabel.text = if (format == DocumentFormat.PDF) strings.pdfNotice else strings.ready
    }

    private fun chooseOutput() {
        val input = inputField.text.takeIf(String::isNotBlank)?.let(::File)
        if (input == null) {
            JOptionPane.showMessageDialog(this, strings.chooseInput, strings.errorTitle, JOptionPane.ERROR_MESSAGE)
            return
        }
        val extension = if (DocumentFormat.from(input) == DocumentFormat.PDF) "docx" else input.extension.lowercase()
        val chooser = JFileChooser(input.parentFile).apply {
            dialogTitle = strings.chooseOutput
            selectedFile = File(outputField.text)
            fileFilter = FileNameExtensionFilter(extension.uppercase(), extension)
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile
        outputField.text = if (selected.extension.equals(extension, ignoreCase = true)) {
            selected.absolutePath
        } else {
            "${selected.absolutePath}.$extension"
        }
    }

    private fun setRunning(value: Boolean) {
        running = value
        inputButton.isEnabled = !value
        outputButton.isEnabled = !value
        primaryButton.isEnabled = !value
        primaryButton.text = strings.translate
        cancelButton.text = if (value) strings.cancel else strings.close
    }
}

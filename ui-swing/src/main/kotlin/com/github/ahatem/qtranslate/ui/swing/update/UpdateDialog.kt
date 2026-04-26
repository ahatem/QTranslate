package com.github.ahatem.qtranslate.ui.swing.update

import com.formdev.flatlaf.FlatClientProperties
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import javax.swing.*

class UpdateDialog(owner: Frame) : JDialog(owner, true) {

    private val headerLabel = JLabel()
    private val detailsLabel = JLabel()
    private val releaseNotesArea = JTextArea()
    private val skipButton = JButton()
    private val remindLaterButton = JButton()
    private val downloadButton = JButton()

    private var downloadUrl: String? = null
    private var onSkip: (() -> Unit)? = null
    private var onRemindLater: (() -> Unit)? = null

    init {
        defaultCloseOperation = HIDE_ON_CLOSE
        isResizable = true

        headerLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3")

        releaseNotesArea.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = UIManager.getFont("TextArea.font")
            background = UIManager.getColor("Panel.background")
        }

        val notesScrollPane = JScrollPane(releaseNotesArea).apply {
            preferredSize = Dimension(480, 200)
            border = BorderFactory.createTitledBorder("")
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(skipButton)
            add(Box.createHorizontalStrut(8))
            add(remindLaterButton)
            add(Box.createHorizontalGlue())
            add(downloadButton)
        }
        downloadButton.putClientProperty("JButton.buttonType", "default")

        val contentPanel = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(20, 24, 20, 24)
            add(headerLabel, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 8)).apply {
                isOpaque = false
                add(detailsLabel, BorderLayout.NORTH)
                add(notesScrollPane, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }

        contentPane = contentPanel

        skipButton.addActionListener {
            onSkip?.invoke()
            isVisible = false
        }
        remindLaterButton.addActionListener {
            onRemindLater?.invoke()
            isVisible = false
        }
        downloadButton.addActionListener {
            downloadUrl?.let { url ->
                runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }
            }
            isVisible = false
        }
    }

    fun show(state: UpdateDialogState) {
        title = state.title
        headerLabel.text = state.header
        detailsLabel.text = "<html>${state.details}</html>"
        releaseNotesArea.text = state.releaseNotes
        releaseNotesArea.caretPosition = 0
        skipButton.text = state.skipButton
        remindLaterButton.text = state.remindLaterButton
        downloadButton.text = state.downloadButton
        downloadButton.isEnabled = state.downloadUrl != null
        downloadUrl = state.downloadUrl
        onSkip = state.onSkip
        onRemindLater = state.onRemindLater

        pack()
        minimumSize = Dimension(480, 360)
        setLocationRelativeTo(owner)
        isVisible = true
    }
}

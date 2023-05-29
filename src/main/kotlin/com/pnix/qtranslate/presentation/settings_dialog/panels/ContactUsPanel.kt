package com.pnix.qtranslate.presentation.settings_dialog.panels

import com.formdev.flatlaf.FlatClientProperties
import com.pnix.qtranslate.common.EmailSender
import com.pnix.qtranslate.utils.GBHelper
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*


class ContactUsPanel : JPanel() {
  private val topicField: JTextField
  private val emailField: JTextField
  private val messageArea: JTextArea

  private val pos = GBHelper().apply { insets = Insets(2, 2, 2, 2) }

  init {
    layout = GridBagLayout()

    // Add email label and field
    add(JLabel("Email:"), pos)
    emailField = JTextField(20).apply {
      putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Your email (eg. name@example.com)")
    }
    add(emailField, pos.nextCol().expandW())

    // Add topic label and field
    add(JLabel("Topic:"), pos.nextRow())
    topicField = JTextField(20).apply {
      putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Provide a brief summary of your message")
    }
    add(topicField, pos.nextCol().expandW())

    // Add message label and area
    add(JLabel("Message:"), pos.nextRow().align(GridBagConstraints.NORTHWEST))
    messageArea = JTextArea(5, 20)
    val scrollPane = JScrollPane(messageArea)
    add(scrollPane, pos.nextCol().expandW().expandH())

    val status = JLabel("").apply {
      font = font.deriveFont(Font.BOLD)
    }
    add(status, pos.nextRow().nextCol())


    // Add submit button
    val submitButton = JButton("Submit")
    add(submitButton, pos.nextRow().nextCol())


    val emailSendSuccessfullyTimer = Timer(5000) {
      status.text = ""
      status.foreground = UIManager.getColor("Label.foreground")
      submitButton.isEnabled = true
    }.apply { isRepeats = false }

    submitButton.addActionListener {
      val error = validateForm()
      if (!error.isNullOrEmpty()) {
        status.text = error
        status.foreground = UIManager.getColor("Actions.Red")
        submitButton.isEnabled = false
        emailSendSuccessfullyTimer.restart()
        return@addActionListener
      }
      if (EmailSender.sendEmail(emailField.text, topicField.text, messageArea.text)) {
        status.text = "Email sent successfully"
        status.foreground = UIManager.getColor("Actions.Green")
        submitButton.isEnabled = false
        emailSendSuccessfullyTimer.restart()
        emailField.text = ""
        topicField.text = ""
        messageArea.text = ""
      }
    }

  }

  private fun validateForm(): String? {
    val email = emailField.text.trim()
    val topic = topicField.text.trim()
    val message = messageArea.text.trim()

    if (email.isEmpty()) {
      emailField.putClientProperty("JComponent.outline", "error")
      return "Please enter your email address"
    } else {
      emailField.putClientProperty("JComponent.outline", "")
    }

    val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    if (!email.matches(emailRegex)) {
      emailField.putClientProperty("JComponent.outline", "")
      return "Please enter a valid email address"
    } else {
      emailField.putClientProperty("JComponent.outline", "")
    }

    if (topic.isEmpty()) {
      topicField.putClientProperty("JComponent.outline", "error")
      return "Please provide a brief summary of your message"
    } else {
      topicField.putClientProperty("JComponent.outline", "")
    }

    if (message.isEmpty()) {
      messageArea.putClientProperty("JComponent.outline", "error")
      return "Please enter your message"
    } else {
      messageArea.putClientProperty("JComponent.outline", "")
    }

    return null
  }
}
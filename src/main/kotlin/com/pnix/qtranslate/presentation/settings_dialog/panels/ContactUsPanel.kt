package com.pnix.qtranslate.presentation.settings_dialog.panels

import com.formdev.flatlaf.FlatClientProperties
import com.pnix.qtranslate.common.EmailSender
import com.pnix.qtranslate.common.Localizer
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

  private val pos = GBHelper().apply { insets = Insets(0, 2, 0, 2) }

  init {
    layout = GridBagLayout()
    border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    // Add email label and field
    add(JLabel(Localizer.localize("contact_us_panel_text_email")), pos)
    emailField = JTextField(20).apply {
      putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT,
        Localizer.localize("contact_us_panel_input_placeholder_email")
      )
    }
    add(emailField, pos.nextCol().expandW())

    // Add topic label and field
    add(JLabel(Localizer.localize("contact_us_panel_text_topic")), pos.nextRow())
    topicField = JTextField(20).apply {
      putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT,
        Localizer.localize("contact_us_panel_input_placeholder_topic")
      )
    }
    add(topicField, pos.nextCol().expandW())

    // Add message label and area
    add(
      JLabel(Localizer.localize("contact_us_panel_text_message")),
      pos.nextRow().align(GridBagConstraints.FIRST_LINE_START)
    )
    messageArea = JTextArea(5, 20)
    val scrollPane = JScrollPane(messageArea)
    add(scrollPane, pos.nextCol().expandW().expandH())

    val status = JLabel("").apply {
      font = font.deriveFont(Font.BOLD)
    }
    add(status, pos.nextRow().nextCol())


    // Add submit button
    val submitButton = JButton(Localizer.localize("contact_us_panel_button_text_submit"))
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
        status.text = Localizer.localize("contact_us_panel_success_text_email_sent_successfully")
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
      return Localizer.localize("contact_us_panel_error_text_enter_email")
    } else {
      emailField.putClientProperty("JComponent.outline", "")
    }

    val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    if (!email.matches(emailRegex)) {
      emailField.putClientProperty("JComponent.outline", "")
      return Localizer.localize("contact_us_panel_error_text_enter_valid_email")
    } else {
      emailField.putClientProperty("JComponent.outline", "")
    }

    if (topic.isEmpty()) {
      topicField.putClientProperty("JComponent.outline", "error")
      return Localizer.localize("contact_us_panel_error_text_enter_topic")
    } else {
      topicField.putClientProperty("JComponent.outline", "")
    }

    if (message.isEmpty()) {
      messageArea.putClientProperty("JComponent.outline", "error")
      return Localizer.localize("contact_us_panel_error_text_enter_message")
    } else {
      messageArea.putClientProperty("JComponent.outline", "")
    }

    return null
  }
}
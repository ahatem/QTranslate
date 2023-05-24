package com.pnix.qtranslate.presentation.settings_dialog.panels

import com.pnix.qtranslate.common.EmailSender
import com.pnix.qtranslate.utils.GBHelper
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*


class ContactUsPanel : JPanel() {
  private val nameField: JTextField
  private val emailField: JTextField
  private val messageArea: JTextArea

  private val pos = GBHelper().apply { insets = Insets(2, 2, 2, 2) }

  init {
    layout = GridBagLayout()

    // Add name label and field
    val nameLabel = JLabel("Name:")
    add(nameLabel, pos)
    nameField = JTextField(20)
    add(nameField, pos.nextCol().expandW())

    // Add email label and field
    add(JLabel("Email:"), pos.nextRow())
    emailField = JTextField(20)
    add(emailField, pos.nextCol().expandW())

    // Add message label and area
    add(JLabel("Message:"), pos.nextRow().align(GridBagConstraints.NORTHWEST))
    messageArea = JTextArea(5, 20)
    val scrollPane = JScrollPane(messageArea)
    add(scrollPane, pos.nextCol().expandW().expandH())



    // Add submit button
    val submitButton = JButton("Submit")
    add(submitButton, pos.nextRow().nextCol())


    val emailSendSuccessfullyTimer = Timer(3000) {
      submitButton.text = "Submit"
      submitButton.foreground = UIManager.getColor("Button.foreground")
    }.apply { isRepeats = false }

    submitButton.addActionListener {
      if (EmailSender.sendEmail(emailField.text, nameField.text, messageArea.text)) {
        submitButton.text = "Email sent successfully"
        submitButton.foreground = UIManager.getColor("Actions.Green")
        emailSendSuccessfullyTimer.restart()
      }
    }



  }
}
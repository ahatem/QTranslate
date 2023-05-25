package com.pnix.qtranslate.presentation.about_dialog


import com.pnix.qtranslate.utils.setPadding
import java.awt.*
import javax.swing.*

class AboutQTranslateDialog(parent: Frame) : JDialog(parent, "About QTranslate", true) {

  init {
    layout = BorderLayout()
    preferredSize = Dimension(395,210)
    setPadding(4)

    val title = JLabel("QTranslate").apply {
      font = font.deriveFont(Font.BOLD, 20f)
      alignmentX = Component.CENTER_ALIGNMENT
    }
    val version = JLabel("Version  1.0.0").apply {
      alignmentX = Component.CENTER_ALIGNMENT
    }

    val descriptionText = "QTranslate is a Swing application that allows you to translate text from one language to another using various APIs such as Google, Bing, Yandex, etc."
    val description = JLabel("<HTML><div style=\"text-align: center;\">$descriptionText</div></HTML>").apply {
        alignmentX = Component.CENTER_ALIGNMENT
        font = font.deriveFont(font.size + 1f)
      }

    val closeButton = JButton("Close").apply { addActionListener { dispose() } }

    val topPanel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(title)
      add(version)
    }
    val centerPanel = JPanel(BorderLayout()).apply { add(description) }
    val bottomPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply { add(closeButton) }


    add(topPanel, BorderLayout.NORTH)
    add(centerPanel, BorderLayout.CENTER)
    add(bottomPanel, BorderLayout.SOUTH)

    pack()
    setLocationRelativeTo(parent)

    isVisible = true
  }
}

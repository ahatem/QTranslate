package com.pnix.qtranslate.presentation.about_dialog


import com.pnix.qtranslate.common.Localizer
import com.pnix.qtranslate.common.QTranslate
import com.pnix.qtranslate.utils.setPadding
import java.awt.*
import javax.swing.*

class AboutQTranslateDialog(frame: Frame) : JDialog(frame, Localizer.localize("about_panel_title"), true) {

  init {
    layout = BorderLayout()
    preferredSize = Dimension(395, 210)
    isResizable = false
    setPadding(4)

    val title = JLabel("QTranslate").apply {
      font = font.deriveFont(Font.BOLD, 20f)
      alignmentX = Component.CENTER_ALIGNMENT
    }
    val version = JLabel(Localizer.localize("about_panel_text_version").format(QTranslate.VERSION_NAME)).apply {
      alignmentX = Component.CENTER_ALIGNMENT
    }

    val descriptionText = Localizer.localize("about_panel_text_what_is_qtranslate")
    val description = JLabel("<HTML><div style=\"text-align: center;\">$descriptionText</div></HTML>").apply {
      alignmentX = Component.CENTER_ALIGNMENT
      font = font.deriveFont(font.size + 1f)
    }

    val closeButton = JButton(Localizer.localize("about_panel_button_text_close")).apply { addActionListener { dispose() } }

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

    rootPane.defaultButton = closeButton

    pack()
    setLocationRelativeTo(frame)
    applyComponentOrientation(frame.componentOrientation)
    isVisible = true
  }
}

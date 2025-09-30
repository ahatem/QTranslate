package com.github.ahatem.qtranslate.presentation.update_dialog

import com.github.ahatem.qtranslate.common.LatestVersionInfo
import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.common.QTranslate
import com.github.ahatem.qtranslate.models.Configurations
import com.github.ahatem.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.github.ahatem.qtranslate.presentation.viewmodels.UnknownErrorOccurredException
import com.github.ahatem.qtranslate.utils.setPadding
import java.awt.*
import java.net.URL
import javax.swing.*

class NewUpdateDialog(frame: Frame, latestVersionInfo: LatestVersionInfo) : JDialog(frame, Localizer.localize("new_update_panel_title"), true) {

  init {
    preferredSize = Dimension(465, 295)
    layout = BorderLayout()
    setPadding(4)

    val headerTitle =
      JLabel(Localizer.localize("new_update_panel_text_new_version_available")).apply { putClientProperty("FlatLaf.styleClass", "h3") }

    val headerText = Localizer.localize("new_update_panel_text_new_version_available_details").format(
      latestVersionInfo.versionName,
      QTranslate.VERSION_NAME
    )
    val headerInfo = JLabel("<HTML><div style=\"text-align: start;\">$headerText</div></HTML>").apply {
      alignmentX = Component.CENTER_ALIGNMENT
      border = BorderFactory.createEmptyBorder(8, 0, 10, 0)
    }

    val releaseNotesLabel = JLabel(Localizer.localize("new_update_panel_text_release_notes")).apply { putClientProperty("FlatLaf.styleClass", "h4") }
    val releaseNotesTextPane = JTextPane().apply {
      contentType = "text/html"
      isEditable = false

      val htmlText = """
            <html>
                <head>
                    <style type='text/css'>
                        ul {margin:0 8px; list-style-type: "- ";}
                        li {font-size: large;}
                    </style>
                </head>
                <body>
                   ${latestVersionInfo.releaseNotes}
                </body>
            </html>
        """.trimIndent().trim()
      text = htmlText
    }
    val releaseNotesScrollPane = JScrollPane(releaseNotesTextPane).apply {
      border = BorderFactory.createCompoundBorder(
        BorderFactory.createEmptyBorder(2, 0, 2, 0),
        BorderFactory.createMatteBorder(1, 1, 1, 1, UIManager.getColor("Component.borderColor"))
      )
    }
    UIManager.addPropertyChangeListener { evt ->
      if ("lookAndFeel" == evt.propertyName) releaseNotesScrollPane.border =
        BorderFactory.createCompoundBorder(
          BorderFactory.createEmptyBorder(2, 0, 2, 0),
          BorderFactory.createMatteBorder(1, 1, 1, 1, UIManager.getColor("Component.borderColor"))
        )
    }

    val skipButton = JButton(Localizer.localize("new_update_panel_button_text_skip")).apply {
      addActionListener { Configurations.skippedVersionNumber = latestVersionInfo.versionCode; dispose(); }
    }
    val remindLaterButton = JButton(Localizer.localize("new_update_panel_button_text_remind_later")).apply { addActionListener { dispose() } }
    val downloadButton = JButton(Localizer.localize("new_update_panel_button_text_go_to_webpage")).apply {
      addActionListener {
        runCatching { Desktop.getDesktop().browse(URL("https://qtranslate-app.web.app/").toURI()) }
          .onFailure { QTranslateViewModel.setError(UnknownErrorOccurredException(it)) }
      }
    }

    val headerLayout = JPanel(BorderLayout()).apply {
      add(headerTitle, BorderLayout.NORTH)
      add(headerInfo, BorderLayout.SOUTH)
    }

    val releaseNotesLayout = JPanel(BorderLayout()).apply {
      add(releaseNotesLabel, BorderLayout.NORTH)
      add(releaseNotesScrollPane, BorderLayout.CENTER)
    }

    val buttonsLayout = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.LINE_AXIS)
      add(skipButton)
      add(Box.createHorizontalGlue())
      add(remindLaterButton)
      add(downloadButton)
    }

    add(headerLayout, BorderLayout.NORTH)
    add(releaseNotesLayout, BorderLayout.CENTER)
    add(buttonsLayout, BorderLayout.SOUTH)

    getRootPane().defaultButton = downloadButton

    pack()
    setLocationRelativeTo(frame)
    applyComponentOrientation(frame.componentOrientation)
    isVisible = true
  }
}
package com.github.ahatem.qtranslate.presentation.dictionary_dialog

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.icons.FlatSearchIcon
import com.github.ahatem.qtranslate.common.Localizer
import com.github.ahatem.qtranslate.presentation.components.BrowserPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*


class DictionaryDialog(frame: Frame) : JDialog(frame, "Browser", false) {

  init {
    layout = BorderLayout()
    val browserPanel1 = BrowserPanel()

    val topLayout = JPanel(BorderLayout()).apply {
      add(JTextField("https://qtranslate-app.web.app/").apply {
        putClientProperty(
          FlatClientProperties.PLACEHOLDER_TEXT,
          Localizer.localize("history_panel_input_placeholder_search")
        )
        putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, FlatSearchIcon())
        putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
        addActionListener { browserPanel1.setUrl(text) }
      })

      add(JButton("Go").apply { addActionListener { browserPanel1.setUrl(text) } }, BorderLayout.LINE_END)
    }

    val tabs = JTabbedPane().apply {
      putClientProperty("JTabbedPane.showTabSeparators", true)
      putClientProperty("JTabbedPane.tabType", "card")
      putClientProperty("JTabbedPane.tabWidthMode", "equal")
      putClientProperty("JTabbedPane.tabAreaAlignment", "fill")

      add("Wikipedia", JLabel())
      add("Stackoverflow", JLabel())
      add("QTranslate", JLabel())

      addChangeListener {
        when (selectedIndex) {
          0 -> browserPanel1.setUrl("https://en.wikipedia.org/wiki/Rift_Valley_fever")
          1 -> browserPanel1.setUrl("https://stackoverflow.com/questions/7130980/swt-browser-no-more-handles-error")
          2 -> browserPanel1.setUrl("https://qtranslate-app.web.app/")
        }
        revalidate()
        repaint()
      }
    }

//    add(topLayout, BorderLayout.NORTH)
    add(JPanel(BorderLayout()).apply {
      add(tabs, BorderLayout.NORTH)
      add(browserPanel1)
    })

    addWindowListener(object : WindowAdapter() {
      override fun windowClosing(e: WindowEvent?) {
        browserPanel1.dispose()
      }
    })


    preferredSize = Dimension(preferredSize.width + 250 - 1, (preferredSize.height + 300 - 1))
    pack()

    setLocationRelativeTo(frame)
    componentOrientation = frame.componentOrientation
    this.isVisible = true

    if (browserPanel1.initialize()) {
      browserPanel1.setUrl("https://en.wikipedia.org/wiki/Rift_Valley_fever")
      size = Dimension(preferredSize.width + 1, preferredSize.height + 1)
    }
    else println("Failed to initialise browser")

  }

}


package com.pnix.qtranslate.presentation.options

import com.pnix.qtranslate.domain.models.Configuration
import com.pnix.qtranslate.domain.models.Language
import com.pnix.qtranslate.presentation.main_frame.QTranslateViewModel
import com.pnix.qtranslate.utils.GBHelper
import com.pnix.qtranslate.utils.addSeparator
import java.awt.GraphicsEnvironment
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel


class BasicsPanel(configuration: Configuration) : JPanel() {

  private val pos = GBHelper().apply { insets = Insets(2, 2, 2, 2) }

  init {
    layout = GridBagLayout()
    addSeparator(pos, "General")
    add(
      JCheckBox("Start with windows", configuration.startWithWindows).apply {
        addActionListener {
          configuration.startWithWindows = isSelected
        }
      },
      pos.nextRow().expandW()
    )

    add(
      JLabel("Interface language:"),
      pos.nextRow()
    )
    add(
      JComboBox(arrayOf("English")).apply {
        selectedItem = configuration.interfaceLanguage
        addActionListener {
          configuration.interfaceLanguage = selectedItem as String
        }
      },
      pos.nextCol().expandW().width(2)
    )

    add(
      JLabel("Font name:"),
      pos.nextRow()
    )
    add(
      JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames).apply {
        selectedItem = configuration.inputsFontName
        addActionListener {
          configuration.inputsFontName = selectedItem as String
        }
      },
      pos.nextCol().expandW().width(2)
    )

    add(
      JLabel("Font size:"),
      pos.nextRow()
    )
    add(
      JComboBox(generateFontSizes(8, 72).toList().toTypedArray()).apply {
        selectedItem = configuration.inputsFontSize
        addActionListener {
          configuration.inputsFontSize = selectedItem as Int
        }
      },
      pos.nextCol().expandW().width(2)
    )

    addSeparator(pos, "Auto-detect languages")
    add(
      JLabel("First language:"),
      pos.nextRow()
    )
    add(
      JComboBox(QTranslateViewModel.supportedLanguages).apply {
        selectedItem = Language(configuration.autoDetectFirstLanguage)
        addActionListener {
          configuration.autoDetectFirstLanguage = (selectedItem as Language).alpha3
        }
      },
      pos.nextCol().expandW().width(2)
    )

    add(
      JLabel("Second language:"),
      pos.nextRow()
    )
    add(
      JComboBox(QTranslateViewModel.supportedLanguages).apply {
        selectedItem = Language(configuration.autoDetectSecondLanguage)
        addActionListener {
          configuration.autoDetectSecondLanguage = (selectedItem as Language).alpha3
        }
      },
      pos.nextCol().expandW().width(2)
    )

    addSeparator(pos, "History")
    add(
      JCheckBox("Enable history", configuration.enableHistory).apply {
        addActionListener {
          configuration.enableHistory = isSelected
        }
      },
      pos.nextRow().expandW()
    )

    add(
      JCheckBox("Clear history on exit", configuration.clearHistoryOnExist).apply {
        addActionListener {
          configuration.clearHistoryOnExist = isSelected
        }
      },
      pos.nextRow().expandW()
    )

    add(JPanel(), pos.nextRow().expandH())
  }

  private fun generateFontSizes(startSize: Int, endSize: Int): Sequence<Int> {
    val step = if (startSize <= 18) 2 else if (startSize <= 36) 4 else 8
    return generateSequence(startSize) { it + step }.takeWhile { it <= endSize }
  }
}
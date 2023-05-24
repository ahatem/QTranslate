package com.pnix.qtranslate.presentation.settings_dialog.panels

import com.pnix.qtranslate.models.Configuration
import com.pnix.qtranslate.models.Theme
import com.pnix.qtranslate.utils.GBHelper
import com.pnix.qtranslate.utils.SimpleDocumentListener
import com.pnix.qtranslate.utils.addSeparator
import java.awt.GridBagLayout
import java.awt.Insets
import java.text.NumberFormat
import javax.swing.*
import javax.swing.text.NumberFormatter

class AppearancePanel(configuration: Configuration) : JPanel() {

  private val pos = GBHelper().apply { insets = Insets(2, 2, 2, 2) }

  init {
    layout = GridBagLayout()

    addSeparator(pos, "General")
    add(
      JCheckBox("Enable window styling", configuration.enableWindowStyle).apply {
        addActionListener {
          configuration.enableWindowStyle = isSelected
        }
      },
      pos.nextRow().expandW()
    )

    add(
      JCheckBox("Unify window title bar", configuration.unifyTitleBar).apply {
        isEnabled = configuration.enableWindowStyle
        addActionListener {
          configuration.unifyTitleBar = isSelected
        }
      },
      pos.nextRow().expandW()
    )

    add(
      JLabel("Theme:"),
      pos.nextRow()
    )

    add(
      JComboBox(Theme.values().map { it.readableName }.toTypedArray()).apply {
        selectedItem = configuration.theme.readableName
        addActionListener {
          val theme = Theme.getThemeByReadableName(selectedItem as String)
          configuration.theme = theme
        }
      },
      pos.nextCol().expandW().width(2)
    )


    addSeparator(pos, "Popup Window")

    add(
      JCheckBox("Enable auto size", configuration.popupEnableAutoSize).apply {
        addActionListener {
          configuration.popupEnableAutoSize = isSelected
        }
      },
      pos.nextRow().expandW()
    )

    add(
      JCheckBox("Enable auto position", configuration.popupEnableAutoPosition).apply {
        addActionListener {
          configuration.popupEnableAutoPosition = isSelected
        }
      },
      pos.nextRow().expandW()
    )

    add(
      JCheckBox("Pin when dragging", configuration.popupEnablePinWhenDragging).apply {
        addActionListener {
          configuration.popupEnablePinWhenDragging = isSelected
        }
      },
      pos.nextRow().expandW()
    )

    add(
      JLabel("Auto-hide delay:"),
      pos.nextRow()
    )
    add(
      JComboBox(IntArray(12) { it + 1 }.toTypedArray()).apply {
        selectedItem = configuration.popupAutoHideDelay
        addActionListener {
          configuration.popupAutoHideDelay = selectedItem as Int
        }
      },
      pos.nextCol().expandW()
    )
    add(
      JLabel("seconds"),
      pos.nextCol().padding(right = 2, left = 2)
    )

    add(
      JLabel("Transparency (0-90):"),
      pos.nextRow()
    )
    add(
      JFormattedTextField(
        NumberFormatter(NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }).apply {
          valueClass = Integer::class.java
          minimum = 0
          maximum = 90
        }
      ).apply {
        value = configuration.popupTransparency
        document.addDocumentListener(SimpleDocumentListener { _ ->
          configuration.popupTransparency = value as Int
        })
      },
      pos.nextCol().expandW().width(2)
    )

    add(JPanel(), pos.nextRow().expandH())
  }

}
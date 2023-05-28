package com.pnix.qtranslate.utils

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.IntelliJTheme
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.ui.FlatBorder
import com.formdev.flatlaf.util.SystemInfo
import com.pnix.qtranslate.models.Theme
import java.awt.*
import java.awt.event.InputEvent
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener


fun interface SimpleDocumentListener : DocumentListener {
  fun update(e: DocumentEvent?)

  override fun insertUpdate(e: DocumentEvent?) {
    update(e)
  }

  override fun removeUpdate(e: DocumentEvent?) {
    update(e)
  }

  override fun changedUpdate(e: DocumentEvent?) {
    update(e)
  }
}

fun fileToLaf(themeFileName: String): FlatLaf =
  IntelliJTheme.createLaf(Theme::class.java.classLoader.getResourceAsStream("themes/$themeFileName"))

fun createButtonWithIcon(iconPath: String, iconSize: Int, tooltip: String): JButton {
  return JButton().apply {
    toolTipText = tooltip
    icon = FlatSVGIcon(iconPath, iconSize, iconSize).apply {
      colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
    }
  }
}

fun JFrame.setPadding(padding: Int) {
  val emptyBorder = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
  val panel = JPanel(BorderLayout())
  panel.border = emptyBorder
  panel.add(contentPane, BorderLayout.CENTER)
  this.contentPane = panel
}

fun JDialog.setPadding(padding: Int) {
  val emptyBorder = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
  val panel = JPanel(BorderLayout())
  panel.border = emptyBorder
  panel.add(contentPane, BorderLayout.CENTER)
  this.contentPane = panel
}

fun JPanel.addSeparator(pos: GBHelper, text: String) {
  add(
    JLabel(text).apply {
      font = font.deriveFont(Font.BOLD)
      isOpaque = true
      border = BorderFactory.createCompoundBorder(
        FlatBorder(),
        BorderFactory.createEmptyBorder(3, 3, 3, 3)
      )
      val listener = PropertyChangeListener { evt ->
        if ("lookAndFeel" == evt.propertyName) {
          border = BorderFactory.createCompoundBorder(
            FlatBorder(),
            BorderFactory.createEmptyBorder(3, 3, 3, 3)
          )
        }
      }
      addAncestorListener(object : AncestorListener {
        override fun ancestorAdded(event: AncestorEvent) {
          UIManager.addPropertyChangeListener(listener)
        }

        override fun ancestorRemoved(event: AncestorEvent) {
          UIManager.removePropertyChangeListener(listener)
        }

        override fun ancestorMoved(event: AncestorEvent) = Unit
      })
    },
    pos.nextRow().expandW(0.0).width(1).align(GridBagConstraints.WEST)
  )

  add(
    JSeparator(),
    pos.expandW().fill(GridBagConstraints.HORIZONTAL).width(3)
  )
}

fun singleKey(key: Int): KeyStroke {
  return KeyStroke.getKeyStroke(key, 0)
}

fun controlKeyWith(key: Int): KeyStroke {
  return KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
}

fun altKeyWith(key: Int): KeyStroke {
  return KeyStroke.getKeyStroke(key, InputEvent.ALT_DOWN_MASK)
}

fun shiftKeyWith(key: Int): KeyStroke {
  return KeyStroke.getKeyStroke(key, InputEvent.SHIFT_DOWN_MASK)
}

fun getDefaultFontFamily(): String {
  var defaultFontFamily = JLabel().font.family
  if (SystemInfo.isWindows) {
    val winFont = Toolkit.getDefaultToolkit().getDesktopProperty("win.messagebox.font") as Font?
    if (winFont != null) {
      if (SystemInfo.isWinPE) {
        val winPEFont = Toolkit.getDefaultToolkit().getDesktopProperty("win.defaultGUI.font") as Font?
        if (winPEFont != null) {
          defaultFontFamily = winPEFont.family
        }
      } else {
        defaultFontFamily = winFont.family
      }
    }
  }
  return defaultFontFamily
}
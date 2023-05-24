package com.pnix.qtranslate.presentation.main_frame.panels

import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.models.Language
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.copyToClipboard
import com.pnix.qtranslate.utils.createButtonWithIcon
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.KeyEvent
import javax.swing.*


class TranslationOutputPanel : JPanel(BorderLayout()) {

  private val iconSize = 16
  private var fontSize = Configurations.inputsFontSize.toFloat()

  val clearButton = createButtonWithIcon("app-icons/trash.svg", iconSize,"Clear current translation").apply {
    addActionListener(WindowKeyListeners.ClearCurrentTranslation.action)
  }
  val menuButton = createButtonWithIcon("app-icons/menu.svg", iconSize,"").apply {
    val menu = createTranslationOptionsMenu()
    addActionListener {
      if (menu.isVisible) menu.isVisible = false
      else menu.show(this, 0, this.height)
    }
  }
  val swapButton = createButtonWithIcon("app-icons/swap.svg", iconSize,"Change translation direction").apply {
    addActionListener(WindowKeyListeners.SwapTranslationDirection.action)
  }
  val translateButton = JButton("Translate").apply {
    addActionListener(WindowKeyListeners.Translate.action)
  }

  val inputLangComboBox = createComboBox(QTranslateViewModel.inputLanguage.value)
  val outputLangComboBox = createComboBox(QTranslateViewModel.outputLanguage.value)

  val outputTextArea = object : JTextPane() {
    override fun getScrollableTracksViewportWidth(): Boolean {
      return true
    }
  }.apply {
    font = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
    isEditable = false

    addMouseWheelListener { e ->
      if (e.isControlDown) {
        if (e.wheelRotation < 0 && fontSize < 72f) {
          fontSize += 1f
        } else if (e.wheelRotation > 0 && fontSize > 8f) {
          fontSize -= 1f
        }
        font = font.deriveFont(fontSize)
      } else {
        parent.dispatchEvent(e)
      }
    }
  }

  val controlsPanel = createControlsPanel().apply {
    isVisible = Configurations.showTranslationOptionsPanel
  }

  init {
    layout = BorderLayout()
    val scrollPane = JScrollPane(outputTextArea)

    val buttonsPanel = TranslationActionsPanel().apply {
      copyButton.addActionListener { outputTextArea.text.copyToClipboard() }
      listenButton.addActionListener(WindowKeyListeners.ListenToTranslation.action)
    }
    val overlayPanel = JPanel(BorderLayout()).apply {
      add(buttonsPanel, BorderLayout.EAST)
      add(scrollPane, BorderLayout.CENTER)
    }

    add(controlsPanel, BorderLayout.NORTH)
    add(overlayPanel, BorderLayout.CENTER)
  }

  private fun createControlsPanel(): JComponent {
    return JPanel().apply {
      layout = MigLayout("insets 4 2 4 2", "[]3[grow,fill]3[]3[grow,fill]3[]")
      add(clearButton, "width 35::75")
//      add(menuButton, "width 35::25")
      add(inputLangComboBox, "width 0:0:")
      add(swapButton, "width 35::75")
      add(outputLangComboBox, "width 0:0:")
      add(translateButton)
    }
  }

  private fun createTranslationOptionsMenu(): JPopupMenu {
    return JPopupMenu().apply {
      add(JMenuItem("Reset").apply {
        accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, KeyEvent.SHIFT_DOWN_MASK)
      })
      add(JMenuItem("English to Arabic"))
      add(JMenuItem("Arabic to English"))
      add(JSeparator())
      add(JMenuItem("Edit"))
      add(JSeparator())
      add(JCheckBoxMenuItem("Always detect language"))
    }
  }

  private fun createComboBox(selectedLanguage: Language): JComboBox<Language> {
    return JComboBox(QTranslateViewModel.supportedLanguages).apply {
      selectedItem = selectedLanguage
      addItemListener { event ->
        when (val comboBox = event.source as JComboBox<*>) {
          inputLangComboBox -> QTranslateViewModel.setInputLanguage(comboBox.selectedItem as Language)
          outputLangComboBox -> QTranslateViewModel.setOutputLanguage(comboBox.selectedItem as Language)
        }
      }
    }
  }


}
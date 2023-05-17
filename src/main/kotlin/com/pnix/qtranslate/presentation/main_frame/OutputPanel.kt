package com.pnix.qtranslate.presentation.main_frame

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.pnix.qtranslate.domain.models.Configurations
import com.pnix.qtranslate.domain.models.Language
import com.pnix.qtranslate.presentation.actions.ActionManager
import com.pnix.qtranslate.utils.copyToClipboard
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.KeyEvent
import javax.swing.*


class OutputPanel : JPanel(BorderLayout()) {

  private val iconSize = 16
  private var fontSize = Configurations.inputsFontSize.toFloat()

  val clearButton = createButton("app-icons/trash.svg", "Clear current translation").apply {
    addActionListener(ActionManager.actions["clear"])
  }
  val menuButton = createButton("app-icons/menu.svg", "").apply {
    val menu = createTranslationOptionsMenu()
    addActionListener {
      if (menu.isVisible) menu.isVisible = false
      else menu.show(this, 0, this.height)
    }
  }
  val swapButton = createButton("app-icons/swap.svg", "Change translation direction").apply {
    addActionListener(ActionManager.actions["swap"])
  }
  val translateButton = JButton("Translate").apply {
    addActionListener(ActionManager.actions["translate"])
  }

  val inputLangComboBox = createComboBox(QTranslateViewModel.inputLanguage.value)
  val outputLangComboBox = createComboBox(QTranslateViewModel.outputLanguage.value)

  val outputTextArea = JTextArea().apply {
    font = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
    lineWrap = true
    wrapStyleWord = true
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

  private val buttonGroup = ButtonGroup()
  private val translatorsButtons = QTranslateViewModel.translators.mapIndexed { index, it ->
    createToggleButton(it.serviceName, index)
  }

  init {
    val scrollPane = JScrollPane(outputTextArea)
    val buttonsPanel = createButtonsPanel()
    val overlayPanel = JPanel(BorderLayout()).apply {
      add(buttonsPanel, BorderLayout.EAST)
      add(scrollPane)
    }

    add(createControlsPanel(), BorderLayout.NORTH)
    add(overlayPanel, BorderLayout.CENTER)
    add(createTranslatorsPanel(), BorderLayout.SOUTH)
  }

  private fun createTranslatorsPanel(): JToolBar {
    return JToolBar().apply {
      layout = GridLayout(1, 0, 0, 0)
      isFloatable = false
      isRollover = true
      border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
      translatorsButtons.forEach { add(it) }
    }
  }

  private fun createControlsPanel(): JPanel {
    return JPanel(MigLayout("insets 4 2 4 2", "[]3[grow,fill]3[]3[grow,fill]3[]")).apply {
      add(clearButton, "width 35::75")
//      add(menuButton, "width 35::75")
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

  private fun createToggleButton(name: String, index: Int) = JToggleButton(name).apply {
    icon = FlatSVGIcon("translator-icons/${name.lowercase()}.svg", (iconSize * 1.25).toInt(), (iconSize * 1.25).toInt())
    addActionListener {
      QTranslateViewModel.setSelectedTranslatorIndex(index)
      ActionManager.actions["translate"]?.actionPerformed(it)
    }
    buttonGroup.add(this)
  }

  private fun createButtonsPanel() = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)

    add(createButton("app-icons/star.svg", "Toggle favourite").apply {
      isVisible = false
    })
    add(createButton("app-icons/copy-alt.svg", "Copy translation").apply {
      addActionListener { outputTextArea.text.copyToClipboard() }
    })
    add(createButton("app-icons/headphones.svg", "Listen to translation").apply {
      addActionListener(ActionManager.actions["listen_to_translation"])
    })
  }

  private fun createButton(iconPath: String, tooltip: String): JButton {
    return JButton().apply {
      toolTipText = tooltip
      icon = FlatSVGIcon(iconPath, iconSize, iconSize).apply {
        colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
      }
    }
  }
}
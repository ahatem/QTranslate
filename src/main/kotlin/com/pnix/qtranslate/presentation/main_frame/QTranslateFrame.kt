package com.pnix.qtranslate.presentation.main_frame

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.components.FlatButton
import com.melloware.jintellitype.JIntellitype
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.models.TranslationHistory
import com.pnix.qtranslate.presentation.components.JXTrayIcon
import com.pnix.qtranslate.presentation.listeners.global.QTranslateHotkeyListener
import com.pnix.qtranslate.presentation.listeners.window.WindowKeyListeners
import com.pnix.qtranslate.presentation.loading_dialog.LoadingDialog
import com.pnix.qtranslate.presentation.main_frame.menus.OptionsPopupMenu
import com.pnix.qtranslate.presentation.main_frame.menus.TrayPopupMenu
import com.pnix.qtranslate.presentation.main_frame.panels.CenterPanel
import com.pnix.qtranslate.presentation.main_frame.panels.HistoryNavigationPanel
import com.pnix.qtranslate.presentation.main_frame.panels.TranslationInputPanel
import com.pnix.qtranslate.presentation.main_frame.panels.TranslatorsPanel
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import com.pnix.qtranslate.utils.setPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.image.BufferedImage
import java.util.*
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.system.exitProcess

class QTranslateFrame : JFrame("QTranslate") {
  private val loadingDialog = LoadingDialog()

  private val historyNavigationPanel = HistoryNavigationPanel().apply {
    isVisible = Configurations.showHistoryPanel
  }
  private val centerPanel = CenterPanel()
  private val translatorsPanel = TranslatorsPanel().apply {
    isVisible = Configurations.showServicesPanel
  }

  init {
    defaultCloseOperation = DO_NOTHING_ON_CLOSE
    minimumSize = Dimension(450, 260)
    iconImages = getIcons()
    setPadding(4)

    initWindowListeners()
    initMenuBar()

    add(historyNavigationPanel, BorderLayout.NORTH)
    add(centerPanel)
    add(translatorsPanel, BorderLayout.SOUTH)

    pack()
    isLocationByPlatform = true

    QTranslateViewModel.setMainFrame(this@QTranslateFrame)
    GlobalScope.launch(Dispatchers.Swing) {
      launch {
        QTranslateViewModel.configurationChanged.collectLatest {
          applyConfigurations()
        }
      }
      launch {
        QTranslateViewModel.isLoading.collectLatest {
          loadingDialog.isVisible = it
        }
      }
      launch {
        QTranslateViewModel.isTranslating.collectLatest {
          disableInputs(it)
        }
      }
      launch {
        QTranslateViewModel.selectedTranslatorIndex.collectLatest {
          historyNavigationPanel.updateStatus()
          val buttons = Collections.list(translatorsPanel.buttonGroup.elements).toList()
          buttons.withIndex().forEach { (index, button) ->
            if (index == it && !button.isSelected) button.isSelected = true
          }
        }
      }
      launch {
        QTranslateViewModel.translation.collectLatest {
          historyNavigationPanel.buttonHistoryBackward.isEnabled = TranslationHistory.canUndo()
          historyNavigationPanel.buttonHistoryForward.isEnabled = TranslationHistory.canRedo()
          if (centerPanel.translationOutputPanel.outputTextArea.text != it) {
            val locale = Locale.forLanguageTag(QTranslateViewModel.outputLanguage.value.alpha2)
            centerPanel.translationOutputPanel.outputTextArea.componentOrientation =
              ComponentOrientation.getOrientation(locale)
            centerPanel.translationOutputPanel.outputTextArea.text = it
            centerPanel.translationOutputPanel.outputTextArea.caretPosition = 0
          }
        }
      }
      launch {
        QTranslateViewModel.backwardTranslation.collectLatest {
          if (centerPanel.translationBackwardPanel.backwardTranslationTextArea.text != it) {
            val locale = Locale.forLanguageTag(QTranslateViewModel.inputLanguage.value.alpha2)
            centerPanel.translationBackwardPanel.backwardTranslationTextArea.componentOrientation =
              ComponentOrientation.getOrientation(locale)
            centerPanel.translationBackwardPanel.backwardTranslationTextArea.text = it
            centerPanel.translationBackwardPanel.backwardTranslationTextArea.caretPosition = 0
          }
        }
      }
      launch {
        QTranslateViewModel.input.debounce(100L).collectLatest {
          if (centerPanel.translationInputPanel.inputTextArea.text != it) {
            val locale = Locale.forLanguageTag(QTranslateViewModel.inputLanguage.value.alpha2)
            centerPanel.translationInputPanel.inputTextArea.componentOrientation =
              ComponentOrientation.getOrientation(locale)
            centerPanel.translationInputPanel.inputTextArea.text = it
          }
        }
      }
      launch {
        QTranslateViewModel.inputLanguage.collectLatest {
          historyNavigationPanel.updateStatus()
          if (centerPanel.translationOutputPanel.inputLangComboBox.selectedItem != it) {
            centerPanel.translationOutputPanel.inputLangComboBox.selectedItem = it
          }
        }
      }
      launch {
        QTranslateViewModel.outputLanguage.collectLatest {
          historyNavigationPanel.updateStatus()
          if (centerPanel.translationOutputPanel.outputLangComboBox.selectedItem != it) {
            centerPanel.translationOutputPanel.outputLangComboBox.selectedItem = it
          }
        }
      }

      launch {
        QTranslateViewModel.spells.collectLatest {
          centerPanel.translationInputPanel.inputTextArea.highlighter.removeAllHighlights()
          for (word in it.corrections) {
            centerPanel.translationInputPanel.inputTextArea.highlighter.addHighlight(
              word.startIndex,
              word.endIndex,
              TranslationInputPanel.misspelledHighlighter
            )
            centerPanel.translationInputPanel.repaint()
          }
        }
      }
    }
  }

  private fun applyConfigurations() {
    centerPanel.showBackwardTranslation(Configurations.showBackwardTranslationPanel)
    historyNavigationPanel.isVisible = Configurations.showHistoryPanel
    centerPanel.translationOutputPanel.controlsPanel.isVisible = Configurations.showTranslationOptionsPanel
    translatorsPanel.isVisible = Configurations.showServicesPanel

    val newFont = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
    centerPanel.translationInputPanel.inputTextArea.apply { font = newFont }
    centerPanel.translationOutputPanel.outputTextArea.apply { font = newFont }
    centerPanel.translationBackwardPanel.backwardTranslationTextArea.apply { font = newFont }

    FlatLaf.setup(Configurations.theme.lookAndFeel)
    FlatLaf.setUseNativeWindowDecorations(Configurations.enableWindowStyle)
    UIManager.put("TitlePane.unifiedBackground", Configurations.unifyTitleBar)

    FlatLaf.updateUILater()
  }

  private fun getIcons(): List<BufferedImage> {
    return listOf(8, 16, 24, 32, 48, 64, 96, 128, 256).map {
      ImageIO.read(javaClass.classLoader.getResourceAsStream("app-icons/app/${it}.png"))
    }
  }

  private fun initWindowListeners() {
    addWindowListener(object : WindowAdapter() {
      override fun windowOpened(e: WindowEvent?) {
        centerPanel.translationInputPanel.inputTextArea.requestFocus()
        JIntellitype.getInstance().addHotKeyListener(QTranslateHotkeyListener(this@QTranslateFrame))
      }

      override fun windowClosing(e: WindowEvent?) {
        state = ICONIFIED
        isVisible = false
        super.windowClosing(e)
      }

      override fun windowClosed(e: WindowEvent?) {
        JIntellitype.getInstance().cleanUp()
        System.runFinalization()
        exitProcess(0)
      }
    })
    addWindowStateListener(object : WindowAdapter() {
      val tray = SystemTray.getSystemTray()
      val trayIconImage = ImageIO.read(javaClass.classLoader.getResourceAsStream("app-icons/app/128.png"))
      val trayIcon = JXTrayIcon(
        trayIconImage.getScaledInstance(tray.trayIconSize.width, tray.trayIconSize.height, Image.SCALE_SMOOTH)
          .apply {},
        "QTranslate"
      )

      init {
        trayIcon.jPopupMenu = TrayPopupMenu()
        trayIcon.addActionListener {
          isVisible = true
          state = NORMAL
        }
        tray.add(trayIcon)
      }
    })

    addWindowFocusListener(object : WindowFocusListener {
      override fun windowGainedFocus(e: WindowEvent?) {
        centerPanel.translationInputPanel.inputTextArea.isFocusable = true
      }

      override fun windowLostFocus(e: WindowEvent?) {
        centerPanel.translationInputPanel.inputTextArea.isFocusable = false
      }
    })

    WindowKeyListeners.getAllValues().forEach {
      rootPane.registerKeyboardAction(it.action, it.hotkey, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
    }
    rootPane.registerKeyboardAction(
      {
        this@QTranslateFrame.isVisible = false
        this@QTranslateFrame.state = ICONIFIED
      },
      KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
      JComponent.WHEN_IN_FOCUSED_WINDOW
    )
  }

  private fun initMenuBar() {
    val settingsButton = createSettingsButton()
    JMenuBar().apply {
      add(Box.createGlue())
      add(settingsButton)
    }.also { jMenuBar = it }
  }

  private fun disableInputs(disable: Boolean) {
    centerPanel.translationInputPanel.inputTextArea.isEditable = !disable

    centerPanel.translationOutputPanel.clearButton.isEnabled = !disable
    centerPanel.translationOutputPanel.menuButton.isEnabled = !disable
    centerPanel.translationOutputPanel.outputTextArea.isEnabled = !disable

    centerPanel.translationOutputPanel.inputLangComboBox.isEnabled = !disable
    centerPanel.translationOutputPanel.outputLangComboBox.isEnabled = !disable

    centerPanel.translationOutputPanel.translateButton.isEnabled = !disable
    centerPanel.translationOutputPanel.swapButton.isEnabled = !disable
  }

  private fun createSettingsButton(): FlatButton {
    val settingsButton = FlatButton().apply {
      icon = FlatSVGIcon("app-icons/settings.svg", 18, 18).apply {
        colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
      }
      buttonType = FlatButton.ButtonType.toolBarButton
      addActionListener {
        val popupMenu = OptionsPopupMenu()
        if (popupMenu.isVisible) {
          popupMenu.isVisible = false
        } else {
          popupMenu.show(this, 0, this.height)
        }
      }
    }
    return settingsButton
  }

}
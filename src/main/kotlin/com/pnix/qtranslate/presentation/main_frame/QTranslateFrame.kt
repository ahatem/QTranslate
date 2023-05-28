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
import com.pnix.qtranslate.presentation.main_frame.layouts.LayoutFactory
import com.pnix.qtranslate.presentation.main_frame.layouts.MainPanel
import com.pnix.qtranslate.presentation.main_frame.menus.OptionsPopupMenu
import com.pnix.qtranslate.presentation.main_frame.menus.TrayPopupMenu
import com.pnix.qtranslate.presentation.main_frame.panels.TranslationInputPanel
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
  private var mainPanel: MainPanel

  init {
    defaultCloseOperation = DO_NOTHING_ON_CLOSE
    minimumSize = Dimension(450, 260)
    preferredSize = Dimension(500, 380)
    iconImages = getIcons()
    setPadding(4)

    initWindowListeners()
    createTrayMenu()
    initMenuBar()

    mainPanel = MainPanel(LayoutFactory.getById(Configurations.layoutPreset))
    add(mainPanel)

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
          mainPanel.historyNavigationPanel.buttonHistoryBackward.isEnabled = TranslationHistory.canUndo()
          mainPanel.historyNavigationPanel.buttonHistoryForward.isEnabled = TranslationHistory.canRedo()
          mainPanel.historyNavigationPanel.updateStatus()
          val buttons = Collections.list(mainPanel.translatorsPanel.buttonGroup.elements).toList()
          buttons.withIndex().forEach { (index, button) ->
            if (index == it && !button.isSelected) button.isSelected = true
          }
        }
      }
      launch {
        QTranslateViewModel.translation.collectLatest {
          mainPanel.historyNavigationPanel.buttonHistoryBackward.isEnabled = TranslationHistory.canUndo()
          mainPanel.historyNavigationPanel.buttonHistoryForward.isEnabled = TranslationHistory.canRedo()
          if (mainPanel.translationOutputPanel.outputTextArea.text != it) {
            val locale = Locale.forLanguageTag(QTranslateViewModel.outputLanguage.value.alpha2)
            mainPanel.translationOutputPanel.outputTextArea.componentOrientation =
              ComponentOrientation.getOrientation(locale)
            mainPanel.translationOutputPanel.outputTextArea.text = it
            mainPanel.translationOutputPanel.outputTextArea.caretPosition = 0
          }
        }
      }
      launch {
        QTranslateViewModel.backwardTranslation.collectLatest {
          if (mainPanel.translationBackwardPanel.backwardTranslationTextArea.text != it) {
            val locale = Locale.forLanguageTag(QTranslateViewModel.inputLanguage.value.alpha2)
            mainPanel.translationBackwardPanel.backwardTranslationTextArea.componentOrientation =
              ComponentOrientation.getOrientation(locale)
            mainPanel.translationBackwardPanel.backwardTranslationTextArea.text = it
            mainPanel.translationBackwardPanel.backwardTranslationTextArea.caretPosition = 0
          }
        }
      }
      launch {
        QTranslateViewModel.input.debounce(100L).collectLatest {
          if (mainPanel.translationInputPanel.inputTextArea.text != it) {
            val locale = Locale.forLanguageTag(QTranslateViewModel.inputLanguage.value.alpha2)
            mainPanel.translationInputPanel.inputTextArea.componentOrientation =
              ComponentOrientation.getOrientation(locale)
            mainPanel.translationInputPanel.inputTextArea.text = it
          }
        }
      }
      launch {
        QTranslateViewModel.inputLanguage.collectLatest {
          mainPanel.historyNavigationPanel.updateStatus()
          if (mainPanel.translationOptionsPanel.inputLangComboBox.selectedItem != it) {
            mainPanel.translationOptionsPanel.inputLangComboBox.selectedItem = it
          }
        }
      }
      launch {
        QTranslateViewModel.outputLanguage.collectLatest {
          mainPanel.historyNavigationPanel.updateStatus()
          if (mainPanel.translationOptionsPanel.outputLangComboBox.selectedItem != it) {
            mainPanel.translationOptionsPanel.outputLangComboBox.selectedItem = it
          }
        }
      }

      launch {
        QTranslateViewModel.spells.collectLatest {
          mainPanel.translationInputPanel.inputTextArea.highlighter.removeAllHighlights()
          for (word in it.corrections) {
            mainPanel.translationInputPanel.inputTextArea.highlighter.addHighlight(
              word.startIndex,
              word.endIndex,
              TranslationInputPanel.misspelledHighlighter
            )
            mainPanel.translationInputPanel.repaint()
          }
        }
      }
    }
  }

  // NOTE: this is bad for performance i think :D
  private fun applyConfigurations() {
    mainPanel.changeLayout(LayoutFactory.getById(Configurations.layoutPreset))

    FlatLaf.setup(Configurations.theme.lookAndFeel)
    FlatLaf.setUseNativeWindowDecorations(Configurations.enableWindowStyle)
    UIManager.put("TitlePane.unifiedBackground", Configurations.unifyTitleBar)
    FlatLaf.updateUILater()

    val newFont = Font(Configurations.inputsFontName, Font.PLAIN, Configurations.inputsFontSize)
    mainPanel.translationInputPanel.inputTextArea.apply { font = newFont }
    mainPanel.translationOutputPanel.outputTextArea.apply { font = newFont }
    mainPanel.translationBackwardPanel.backwardTranslationTextArea.apply { font = newFont }

    mainPanel.showBackwardTranslation(Configurations.showBackwardTranslationPanel)
    mainPanel.historyNavigationPanel.isVisible = Configurations.showHistoryPanel
    mainPanel.translationOptionsPanel.isVisible = Configurations.showTranslationOptionsPanel
    mainPanel.translatorsPanel.isVisible = Configurations.showServicesPanel

    revalidate()
    repaint()
  }

  private fun getIcons(): List<BufferedImage> {
    return listOf(8, 16, 24, 32, 48, 64, 96, 128, 256).map {
      ImageIO.read(javaClass.classLoader.getResourceAsStream("app-icons/app/${it}.png"))
    }
  }

  private fun initWindowListeners() {
    addWindowListener(object : WindowAdapter() {
      override fun windowOpened(e: WindowEvent?) {
        mainPanel.translationInputPanel.inputTextArea.requestFocus()
        if (Configurations.enableGlobalHotkeys) QTranslateHotkeyListener.registerGlobalListener()
        else QTranslateHotkeyListener.unRegisterGlobalListener()
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
    addWindowFocusListener(object : WindowFocusListener {
      override fun windowGainedFocus(e: WindowEvent?) {
        mainPanel.translationInputPanel.inputTextArea.isFocusable = true
      }

      override fun windowLostFocus(e: WindowEvent?) {
        mainPanel.translationInputPanel.inputTextArea.isFocusable = false
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

  private fun createTrayMenu() {
    val tray = SystemTray.getSystemTray()
    val trayIconImage = ImageIO.read(javaClass.classLoader.getResourceAsStream("app-icons/app/128.png"))
    val trayIcon = JXTrayIcon(
      trayIconImage.getScaledInstance(tray.trayIconSize.width, tray.trayIconSize.height, Image.SCALE_SMOOTH)
        .apply {},
      "QTranslate"
    )
    trayIcon.jPopupMenu = TrayPopupMenu()
    trayIcon.addActionListener {
      isVisible = true
      state = NORMAL
    }
    tray.add(trayIcon)

    UIManager.addPropertyChangeListener { if ("lookAndFeel" == it.propertyName) trayIcon.jPopupMenu = TrayPopupMenu() }
  }

  private fun initMenuBar() {
    val settingsButton = createSettingsButton()
    JMenuBar().apply {
      add(Box.createGlue())
      add(settingsButton)
    }.also { jMenuBar = it }
  }

  private fun disableInputs(disable: Boolean) {
    mainPanel.translationInputPanel.inputTextArea.isEditable = !disable

    mainPanel.translationOptionsPanel.clearButton.isEnabled = !disable
    mainPanel.translationOptionsPanel.menuButton.isEnabled = !disable
    mainPanel.translationOutputPanel.outputTextArea.isEnabled = !disable

    mainPanel.translationOptionsPanel.inputLangComboBox.isEnabled = !disable
    mainPanel.translationOptionsPanel.outputLangComboBox.isEnabled = !disable

    mainPanel.translationOptionsPanel.translateButton.isEnabled = !disable
    mainPanel.translationOptionsPanel.swapButton.isEnabled = !disable
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
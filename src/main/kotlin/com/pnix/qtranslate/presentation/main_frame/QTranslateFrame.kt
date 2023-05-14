package com.pnix.qtranslate.presentation.main_frame

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.components.FlatButton
import com.melloware.jintellitype.JIntellitype
import com.pnix.qtranslate.domain.models.Configurations
import com.pnix.qtranslate.presentation.actions.ActionManager
import com.pnix.qtranslate.presentation.actions.HotKeyManager
import com.pnix.qtranslate.presentation.components.JXTrayIcon
import com.pnix.qtranslate.presentation.loading.LoadingDialog
import com.pnix.qtranslate.presentation.snipping_screen.SnippingToolDialog
import com.pnix.qtranslate.utils.setPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.util.*
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.system.exitProcess

class QTranslateFrame : JFrame("QTranslate") {

  private val inputPanel = InputPanel()
  private val outputPanel = OutputPanel()
  private val loadingDialog = LoadingDialog()

  init {
    defaultCloseOperation = DO_NOTHING_ON_CLOSE
    minimumSize = Dimension(390, 260)
    preferredSize = Dimension(530, 380)
    iconImages = getIcons()
    setPadding(4)

    initWindowListeners()
    initTrayMenu()

    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, inputPanel, outputPanel).apply { resizeWeight = 0.55 })

    pack()
    setLocationRelativeTo(null)

    GlobalScope.launch(Dispatchers.Swing) {
      launch {
        QTranslateViewModel.isLoading.collectLatest {
          loadingDialog.isVisible = it
        }
      }
      launch {
        QTranslateViewModel.isTranslating.collectLatest { disableInputs(it) }
      }
      launch {
        QTranslateViewModel.translation.collectLatest {
          if (outputPanel.outputTextArea.text != it) {
            outputPanel.outputTextArea.text = it
            val locale = Locale.forLanguageTag(QTranslateViewModel.outputLanguage.value.alpha2)
            outputPanel.outputTextArea.componentOrientation = ComponentOrientation.getOrientation(locale)
          }
        }
      }
      launch {
        QTranslateViewModel.input.collectLatest {
          if (inputPanel.inputTextArea.text != it) {
            inputPanel.inputTextArea.text = it
            val locale = Locale.forLanguageTag(QTranslateViewModel.inputLanguage.value.alpha2)
            inputPanel.inputTextArea.componentOrientation = ComponentOrientation.getOrientation(locale)
          }
        }
      }
      launch {
        QTranslateViewModel.inputLanguage.collectLatest {
          if (outputPanel.inputLangComboBox.selectedItem != it) {
            outputPanel.inputLangComboBox.selectedItem = it
          }
        }
      }
      launch {
        QTranslateViewModel.outputLanguage.collectLatest {
          if (outputPanel.outputLangComboBox.selectedItem != it) {
            outputPanel.outputLangComboBox.selectedItem = it
          }
        }
      }
    }

  }


  private fun getIcons(): List<BufferedImage> {
    return listOf(8, 16, 24, 32, 48, 64, 96, 128, 256).map {
      ImageIO.read(javaClass.classLoader.getResourceAsStream("app-icons/app/${it}.png"))
    }
  }

  private fun initWindowListeners() {
    addWindowListener(object : WindowAdapter() {
      override fun windowOpened(e: WindowEvent?) {
        inputPanel.inputTextArea.requestFocus()
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
        trayIconImage.getScaledInstance(tray.trayIconSize.width, tray.trayIconSize.height, Image.SCALE_SMOOTH).apply {},
        "QTranslate"
      )

      init {
        trayIcon.jPopupMenu = createTrayPopupMenu()
        trayIcon.addActionListener {
          isVisible = true
          state = NORMAL
        }
        tray.add(trayIcon)
      }
    })
    HotKeyManager.hotkeys.forEach { (name, keyStroke) ->
      rootPane.registerKeyboardAction(
        ActionManager.actions[name],
        keyStroke,
        JComponent.WHEN_IN_FOCUSED_WINDOW
      )
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

  private fun initTrayMenu() {
    val settingsButton = createSettingsButton(createOptionsPopupMenu())
    JMenuBar().apply {
      add(Box.createGlue())
      add(settingsButton)
    }.also { jMenuBar = it }
  }

  private fun disableInputs(disable: Boolean) {
    inputPanel.inputTextArea.isEditable = !disable

    outputPanel.clearButton.isEnabled = !disable
    outputPanel.menuButton.isEnabled = !disable
    outputPanel.outputTextArea.isEnabled = !disable

    outputPanel.inputLangComboBox.isEnabled = !disable
    outputPanel.outputLangComboBox.isEnabled = !disable

    outputPanel.translateButton.isEnabled = !disable
    outputPanel.swapButton.isEnabled = !disable
  }

  private fun createTrayPopupMenu(): JPopupMenu {
    val popupMenu = JPopupMenu().apply {
      add(JMenuItem("QTranslate"))
      add(JMenuItem("Dictionary"))
      add(JMenuItem("Text recognition").apply {
        addActionListener {
          this@QTranslateFrame.isVisible = false
          this@QTranslateFrame.state = ICONIFIED
          SwingUtilities.invokeLater {
            Thread.sleep(200)
            SnippingToolDialog(this@QTranslateFrame)
          }
        }
      })
      add(JMenuItem("History"))
      add(JMenuItem("Options"))
      add(JMenuItem("About QTranslate"))
      addSeparator()
      add(JCheckBoxMenuItem("Enable global hotkeys").apply { isSelected = true })
      add(JMenu("Mouse mode"))
      addSeparator()
      add(JMenuItem("Exit").apply { addActionListener { exitProcess(0) } })
    }
    return popupMenu
  }

  private fun createOptionsPopupMenu(): JPopupMenu {
    val popupMenu = JPopupMenu().apply {
      add(JCheckBoxMenuItem("Spell Checking").apply {
        isSelected = Configurations.spellChecking
        addItemListener { event ->
          Configurations.spellChecking = event.stateChange == ItemEvent.SELECTED
        }
      })
      add(JCheckBoxMenuItem("Instant Translation").apply {
        isSelected = Configurations.instantTranslation
        addItemListener { event ->
          Configurations.instantTranslation = event.stateChange == ItemEvent.SELECTED
        }
      })
//      add(createExtendedMenu())
      add(JSeparator())
      add(JMenuItem("Dictionary").apply {
        accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_D, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
      })
      add(JMenuItem("History").apply {
        accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_H, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
      })
      add(JMenuItem("Options"))
      add(JMenuItem("Help").apply { accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0) })
      add(JMenuItem("About QTranslate"))
      add(JSeparator())
      add(JMenuItem("Exit").apply { addActionListener { exitProcess(0) } })
    }
    return popupMenu
  }

  private fun createSettingsButton(popupMenu: JPopupMenu): FlatButton {
    val settingsButton = FlatButton().apply {
      icon = FlatSVGIcon("app-icons/settings.svg", 18, 18).apply {
        colorFilter = FlatSVGIcon.ColorFilter { _: Color? -> UIManager.getColor("Label.foreground") }
      }
      buttonType = FlatButton.ButtonType.toolBarButton
      addActionListener {
        if (popupMenu.isVisible) {
          popupMenu.isVisible = false
        } else {
          popupMenu.show(this, 0, this.height)
        }
      }
    }
    return settingsButton
  }

  private fun createExtendedMenu(): JMenu {
    val extendedMenu = JMenu("Extended").apply {
      add(JMenuItem(" Read phonetically"))
      add(JMenuItem(" Clear input on Drag & Drop"))
      add(JMenuItem(" Auto-cleanup of translation"))
      add(JMenuItem(" Save contents on exit "))
      addSeparator()
      add(JMenuItem(" Show top pane").apply {
        accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F1, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
      })
      add(JMenuItem(" Show middle pane").apply {
        accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F2, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
      })
      add(JMenuItem(" Show services pane").apply {
        accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F3, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
      })
      addSeparator()
      add(JMenuItem(" Minimize to system tray on minimize"))
      add(JMenuItem(" Minimize to system tray on close"))
      addSeparator()
      add(createOnStartupMenu())
    }
    return extendedMenu
  }

  private fun createOnStartupMenu(): JMenu {
    val onStartupMenu = JMenu("On startup")
    onStartupMenu.add(JMenuItem("Show window"))
    onStartupMenu.add(JMenuItem("Minimize to tray"))
    onStartupMenu.add(JMenuItem("Restore previous state"))
    return onStartupMenu
  }

}
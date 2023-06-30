package com.pnix.qtranslate.presentation.listeners.global

import com.melloware.jintellitype.HotkeyListener
import com.melloware.jintellitype.JIntellitype
import com.pnix.qtranslate.models.Hotkeys
import com.pnix.qtranslate.presentation.listeners.window.CycleServicesAction
import com.pnix.qtranslate.presentation.quick_translate_dialog.QuickTranslateDialog
import com.pnix.qtranslate.presentation.snipping_screen_dialog.SnippingToolDialog
import com.pnix.qtranslate.presentation.viewmodels.QTranslateViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

class QTranslateHotkeyListener(private val frame: JFrame) : HotkeyListener {

  companion object {
    private val globalHotkeys = listOf(
      "main_window",
      "popup_window",
      "listen_selected_text",
      "text_recognition",
      "cycle_services"
    )

    private val INSTANCE = QTranslateHotkeyListener(QTranslateViewModel.mainFrame)
    private var registered = false

    fun registerGlobalListener() {
      if (registered) return
      INSTANCE.init()
      JIntellitype.getInstance().addHotKeyListener(INSTANCE)
      registered = true
    }

    fun unRegisterGlobalListener() {
      if (!registered) return
      INSTANCE.clear()
      JIntellitype.getInstance().removeHotKeyListener(INSTANCE)
      registered = false
    }

  }

  private var originalContents: Transferable? = null
  private var usingClipboard = false
  private var lastCtrlPressTime = 0L

  private fun init() {
    globalHotkeys.forEachIndexed { index, hotkeyId ->
      val hotkey = Hotkeys.getHotkey(hotkeyId)!!
      runCatching { JIntellitype.getInstance().registerSwingHotKey(index, hotkey.modifiers, hotkey.keyCode) }
    }
  }

  fun clear() {
    globalHotkeys.forEachIndexed { index, _ ->
      runCatching { JIntellitype.getInstance().unregisterHotKey(index) }
    }
  }

  override fun onHotKey(identifier: Int) {
    when (identifier) {
      0 -> showApp()
      1 -> translateInPlace()
      2 -> listenToTextInPlace()
      3 -> captureScreen()
      4 -> cycleServices()
    }
  }

  private fun showApp() {
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastCtrlPressTime < 500) {
      GlobalScope.launch {
        useUserSelectedText {
          QTranslateViewModel.setInputText(it)
          this.launch { QTranslateViewModel.translate() }
          SwingUtilities.invokeLater {
            frame.isVisible = true
            frame.state = JFrame.NORMAL
          }
        }
      }

    } else {
      lastCtrlPressTime = currentTime
    }
  }

  private var quickTranslateDialog: QuickTranslateDialog? = null

  private fun translateInPlace() {
    GlobalScope.launch {
      useUserSelectedText {
        this.launch {
          QTranslateViewModel.setLoading(true)
          QTranslateViewModel.setInputText(it)
          QTranslateViewModel.translate()
          QTranslateViewModel.setLoading(false)
          withContext(Dispatchers.Swing) {
            SwingUtilities.invokeLater {
              if (quickTranslateDialog == null) quickTranslateDialog = QuickTranslateDialog(frame).apply {
                addWindowListener(object : WindowAdapter() {
                  override fun windowClosed(e: WindowEvent?) {
                    quickTranslateDialog = null
                  }
                })
              }
            }
          }
        }
      }
    }
  }

  private fun listenToTextInPlace() {
    GlobalScope.launch {
      useUserSelectedText {
        this.launch {
          QTranslateViewModel.setLoading(true)
          QTranslateViewModel.setInputText(it)
          QTranslateViewModel.listenToInput()
          QTranslateViewModel.setLoading(false)
        }
      }
    }
  }

  private fun captureScreen() {
    frame.isVisible = false
    frame.state = JFrame.ICONIFIED
    SwingUtilities.invokeLater {
      Thread.sleep(200)
      SnippingToolDialog(frame)
    }
  }

  private suspend fun useUserSelectedText(callback: (String) -> Unit) {
    if (usingClipboard) return

    usingClipboard = true
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    originalContents = clipboard.getContents(null)

    simulateCopy()

    if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) && clipboard.getContents(null) != originalContents) {
      callback.invoke(clipboard.getData(DataFlavor.stringFlavor).toString().trim())
    } else {
      callback.invoke("")
    }

    usingClipboard = false
    originalContents?.let { clipboard.setContents(it, null) }
  }

  private suspend fun simulateCopy() {
    val robot = Robot()
    robot.keyPress(KeyEvent.VK_CONTROL)
    delay(50)
    robot.keyPress(KeyEvent.VK_C)
    delay(150)
    robot.keyRelease(KeyEvent.VK_C)
    delay(50)
    robot.keyRelease(KeyEvent.VK_CONTROL)
    delay(50)
  }

  private fun cycleServices() {
    if (QTranslateViewModel.mainFrame.isVisible) {
      CycleServicesAction().actionPerformed(null)
    }
  }

}


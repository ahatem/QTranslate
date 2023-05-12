package com.pnix.qtranslate.presentation.main_frame

import com.melloware.jintellitype.HotkeyListener
import com.melloware.jintellitype.JIntellitype
import com.pnix.qtranslate.presentation.actions.ActionManager
import com.pnix.qtranslate.presentation.quick_translate.QuickTranslateDialog
import com.pnix.qtranslate.presentation.snipping_screen.SnippingToolDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities


class QTranslateHotkeyListener(private val frame: JFrame) : HotkeyListener {

  private var originalContents: Transferable? = null
  private var usingClipboard = false
  private var lastCtrlPressTime = 0L

  init {
    JIntellitype.getInstance().registerHotKey(0, JIntellitype.MOD_CONTROL, 0)
    JIntellitype.getInstance().registerHotKey(1, JIntellitype.MOD_CONTROL, 'Q'.code)
    JIntellitype.getInstance().registerHotKey(2, JIntellitype.MOD_CONTROL, 'E'.code)
    JIntellitype.getInstance().registerHotKey(3, JIntellitype.MOD_CONTROL, 'B'.code)
  }

  override fun onHotKey(identifier: Int) {
    when (identifier) {
      0 -> showApp()
      1 -> translateInPlace()
      2 -> listenToTextInPlace()
      3 -> captureScreen()
    }
  }

  private fun showApp() {
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastCtrlPressTime < 500) {
      GlobalScope.launch {
        useUserSelectedText {
          QTranslateViewModel.setInputText(it)
          ActionManager.actions["translate"]?.actionPerformed(null)
          frame.isVisible = true
          frame.state = JFrame.NORMAL
        }
      }
    } else {
      lastCtrlPressTime = currentTime
    }
  }

  private fun translateInPlace() {
    GlobalScope.launch {
      useUserSelectedText {
        this.launch {
          QTranslateViewModel.setInputText(it)
          QTranslateViewModel.translateAndWait()
          withContext(Dispatchers.Swing) {
            QuickTranslateDialog(frame)
          }
        }
      }
    }
  }

  private fun listenToTextInPlace() {
    GlobalScope.launch {
      useUserSelectedText {
        this.launch {
          QTranslateViewModel.setInputText(it)
          QTranslateViewModel.listenToInput()
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
    if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
      callback.invoke(clipboard.getData(DataFlavor.stringFlavor).toString().trim())
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
}


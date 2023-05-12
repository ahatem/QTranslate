package com.pnix.qtranslate.presentation.actions

import java.awt.Toolkit
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

object HotKeyManager {
  private val _hotkeys = mutableMapOf<String, KeyStroke>()
  val hotkeys: Map<String, KeyStroke> get() = _hotkeys

  init {
    _hotkeys["clear"] = KeyStroke.getKeyStroke(KeyEvent.VK_N, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
    _hotkeys["swap"] = KeyStroke.getKeyStroke(KeyEvent.VK_I, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
    _hotkeys["translate"] = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
  }
}
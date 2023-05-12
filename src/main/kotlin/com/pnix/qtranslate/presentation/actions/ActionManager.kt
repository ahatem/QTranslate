package com.pnix.qtranslate.presentation.actions

import java.awt.event.ActionListener

object ActionManager {

  private val _actions = mutableMapOf<String, ActionListener>()
  val actions: Map<String, ActionListener> get() = _actions

  init {
    _actions["clear"] = ClearAction()
    _actions["swap"] = SwapAction()
    _actions["translate"] = TranslateAction()
    _actions["listen_to_input"] = ListenToInput()
    _actions["listen_to_translation"] = ListenToTranslation()
  }
}
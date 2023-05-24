package com.pnix.qtranslate.presentation.main_frame.layouts


abstract class Layout {

  companion object {
    private var id = 0
  }

  val presetId: String;
  abstract val presetName: String

  init {
    id++; presetId = "preset_$id"
  }

  abstract fun showBackwardTranslation(mainPanel: MainPanel, show: Boolean)
  abstract fun createLayout(mainPanel: MainPanel)

}
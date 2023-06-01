package com.pnix.qtranslate.presentation.main_frame.layouts

import com.pnix.qtranslate.common.Localizer


abstract class Layout {

  companion object {
    private var id = 0
  }

  val presetId: String
  val presetName: String

  init {
    id++
    presetId = "preset_$id"
    presetName = Localizer.localize("menu_item_layout_preset_text").format(id)
  }

  abstract fun showBackwardTranslation(mainPanel: MainPanel, show: Boolean)
  abstract fun createLayout(mainPanel: MainPanel)

}
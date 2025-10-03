package com.github.ahatem.qtranslate.presentation.main_frame.layouts

object LayoutFactory {

    val availableLayouts = listOf(
        Layout1(),
        Layout2(),
        Layout3(),
        Layout4()
    )

    fun getById(layoutPresetId: String) = availableLayouts.find { it.presetId == layoutPresetId } ?: Layout1()
}
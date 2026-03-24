package com.github.ahatem.qtranslate.ui.swing.main.layout

interface LayoutStrategy {
    val type: LayoutType
    val id: String get() = type.id
    val localizeId: String get() = type.localizeId
    fun arrange(components: ComponentRegistry, isRtl: Boolean = false): ArrangedLayout
}
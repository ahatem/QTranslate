package com.github.ahatem.qtranslate.ui.swing.main.layout

interface LayoutStrategy {
    val type: LayoutType
    val id: String get() = type.id
    val localizeId: String get() = type.localizeId

    /**
     * Builds the arrangement.
     *
     * [historyInline] is true for [com.github.ahatem.qtranslate.core.settings.data.ToolbarStyle.COMPACT]:
     * the history controls share the language row instead of occupying a row of their own. It is a
     * parameter rather than something the strategy looks up because an arrangement is built from
     * the values it is handed, and the same strategy has to be able to build either shape.
     */
    fun arrange(
        components: ComponentRegistry,
        isRtl: Boolean = false,
        historyInline: Boolean = false
    ): ArrangedLayout
}
package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.util.UIScale
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon
import javax.swing.UIManager

/** Resolves an LAF-owned icon on use so a live theme switch also updates its colors and shape. */
internal class ThemeIcon(private val key: String) : Icon {
    private val current: Icon? get() = UIManager.getIcon(key)
    override fun getIconWidth(): Int = current?.iconWidth ?: UIScale.scale(8)
    override fun getIconHeight(): Int = current?.iconHeight ?: UIScale.scale(8)
    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
        current?.paintIcon(c, g, x, y)
    }
}

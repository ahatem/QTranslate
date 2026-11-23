package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.Color
import kotlin.math.roundToInt

/** The same colour with its alpha replaced by [alpha], clamped to 0..255. */
fun Color.withAlpha(alpha: Int): Color = Color(red, green, blue, alpha.coerceIn(0, 255))

/** This colour moved [amount] of the way toward [target]; 0 leaves it untouched. The result is opaque. */
fun Color.blend(target: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        (red + (target.red - red) * t).roundToInt().coerceIn(0, 255),
        (green + (target.green - green) * t).roundToInt().coerceIn(0, 255),
        (blue + (target.blue - blue) * t).roundToInt().coerceIn(0, 255),
    )
}

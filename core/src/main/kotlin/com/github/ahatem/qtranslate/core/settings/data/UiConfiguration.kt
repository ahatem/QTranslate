package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.Serializable

@Serializable
data class ToolbarVisibility(
    val isHistoryBarVisible: Boolean    = true,
    val isLanguageBarVisible: Boolean   = true,
    val isServicesPanelVisible: Boolean = true,
    val isStatusBarVisible: Boolean     = true
) {
    companion object { val DEFAULT = ToolbarVisibility() }
}

@Serializable
data class FontConfig(val name: String, val size: Int) {
    init { require(size > 0) { "Font size must be positive, was $size." } }
}

@Serializable
data class Size(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) { "Size must be positive, was ${width}x${height}." } }
}

/**
 * A saved window position, in the virtual screen coordinates AWT reports.
 *
 * Deliberately unconstrained. This used to require both coordinates to be non-negative, which is
 * simply not true of a real desktop: a display arranged to the left of or above the primary one
 * occupies negative coordinates, and a window sitting on it has an ordinary, valid, negative
 * position. The requirement turned that into an IllegalArgumentException thrown from the middle of
 * saving, so call sites clamped to zero to get past it, which then moved the window to the primary
 * display on the next launch.
 *
 * A position that is no longer on any connected display is a genuine worry, but it belongs to the
 * moment the window is placed rather than the moment the number is stored, because the displays
 * can change in between. `isPositionReachable` in ui-swing handles it there.
 */
@Serializable
data class Position(val x: Int, val y: Int)

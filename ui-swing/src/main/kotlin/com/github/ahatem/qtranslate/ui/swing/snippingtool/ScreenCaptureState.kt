package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage

enum class CaptureMode {
    IDLE,      // Waiting for the user to start dragging
    SELECTING, // The user is drawing the initial rectangle
    SELECTED   // A selection exists, user can now move or resize
}

// Defines what a specific mouse action will do (move, resize corner, etc.)
enum class MouseAction(val cursor: Int) {
    NONE(Cursor.DEFAULT_CURSOR),
    CROSSHAIR(Cursor.CROSSHAIR_CURSOR),
    MOVE(Cursor.MOVE_CURSOR),
    RESIZE_N(Cursor.N_RESIZE_CURSOR),
    RESIZE_S(Cursor.S_RESIZE_CURSOR),
    RESIZE_W(Cursor.W_RESIZE_CURSOR),
    RESIZE_E(Cursor.E_RESIZE_CURSOR),
    RESIZE_NW(Cursor.NW_RESIZE_CURSOR),
    RESIZE_NE(Cursor.NE_RESIZE_CURSOR),
    RESIZE_SW(Cursor.SW_RESIZE_CURSOR),
    RESIZE_SE(Cursor.SE_RESIZE_CURSOR)
}


data class ScreenCaptureState(
    val screenshot: BufferedImage,
    val selection: Rectangle? = null,
    val mode: CaptureMode = CaptureMode.IDLE,
    val mouseAction: MouseAction = MouseAction.CROSSHAIR,
    val showButtons: Boolean = false,
    val buttonsPosition: Point? = null
)
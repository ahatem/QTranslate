package com.pnix.qtranslate.presentation.snipping_screen

import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.MouseMotionListener

class OverlayPanelMouseListener(private val panel: OverlayPanel) : MouseListener, MouseMotionListener {
  companion object {
    const val BORDER_SIZE = 2
    const val EDGE_SIZE = BORDER_SIZE * 2
  }

  enum class ResizeMode {
    RESIZE_NONE,
    RESIZE_NORTH,
    RESIZE_SOUTH,
    RESIZE_WEST,
    RESIZE_EAST,
    RESIZE_NORTHWEST,
    RESIZE_NORTHEAST,
    RESIZE_SOUTHWEST,
    RESIZE_SOUTHEAST
  }

  var selection: Rectangle? = null
  private var selectionPressX = 0
  private var selectionPressY = 0
  var resizeMode = ResizeMode.RESIZE_NONE

  private var mouseX = 0
  private var mouseY = 0
  private var mousePressX = 0
  private var mousePressY = 0

  private var selectionMade = false
  private var isMovingSelection = false

  init {
    panel.addMouseListener(this)
    panel.addMouseMotionListener(this)
  }

  private fun isInside(rect: Rectangle, p: Point): Boolean {
    val borderSize = EDGE_SIZE + BORDER_SIZE
    return rect.contains(p.x, p.y) &&
        (p.x - rect.x >= borderSize) &&
        (rect.x + rect.width - p.x >= borderSize) &&
        (p.y - rect.y >= borderSize) &&
        (rect.y + rect.height - p.y >= borderSize)
  }

  override fun mousePressed(e: MouseEvent) {
    panel.hideButton()

    mousePressX = e.x
    mousePressY = e.y

    if (!selectionMade) return

    isMovingSelection = isInside(selection!!, e.point)
    selectionPressX = selection!!.x
    selectionPressY = selection!!.y
  }

  override fun mouseDragged(e: MouseEvent) {
    var x2 = e.x
    var y2 = e.y
    if (!selectionMade) {
      panel.selectArea(mouseX, mouseY, x2, y2)
      return
    }

    if (isMovingSelection) {
      x2 += selectionPressX
      y2 += selectionPressY
      panel.moveSelectedArea(mousePressX, mousePressY, x2, y2)
      return
    }

    if (resizeMode == ResizeMode.RESIZE_NONE) {
      if (e.x < selection!!.x + EDGE_SIZE && e.y < selection!!.y + EDGE_SIZE) {
        resizeMode = ResizeMode.RESIZE_NORTHWEST
        panel.cursor = Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
      } else if (e.x > selection!!.x + selection!!.width - EDGE_SIZE && e.y < selection!!.y + EDGE_SIZE) {
        resizeMode = ResizeMode.RESIZE_NORTHEAST
        panel.cursor = Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
      } else if (e.x < selection!!.x + EDGE_SIZE && e.y > selection!!.y + selection!!.height - EDGE_SIZE) {
        resizeMode = ResizeMode.RESIZE_SOUTHWEST
        panel.cursor = Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
      } else if (e.x > selection!!.x + selection!!.width - EDGE_SIZE && e.y > selection!!.y + selection!!.height - EDGE_SIZE) {
        resizeMode = ResizeMode.RESIZE_SOUTHEAST
        panel.cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
      } else if (e.x < selection!!.x + EDGE_SIZE) {
        resizeMode = ResizeMode.RESIZE_WEST
        panel.cursor = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
      } else if (e.x > selection!!.x + selection!!.width - EDGE_SIZE) {
        resizeMode = ResizeMode.RESIZE_EAST
        panel.cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
      } else if (e.y < selection!!.y + EDGE_SIZE) {
        resizeMode = ResizeMode.RESIZE_NORTH
        panel.cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
      } else if (e.y > selection!!.y + selection!!.height - EDGE_SIZE) {
        resizeMode = ResizeMode.RESIZE_SOUTH
        panel.cursor = Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
      }
    }

    if (resizeMode != ResizeMode.RESIZE_NONE) {
      panel.resizeSelectedArea(mousePressX, mousePressY, x2, y2)
    }
    mousePressX = x2
    mousePressY = y2
  }

  override fun mouseMoved(e: MouseEvent) {
    mouseX = e.x
    mouseY = e.y

    if (!selectionMade) {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
      return
    }

    if (selection!!.contains(e.point)) {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    }

    val x: Int = selection!!.x
    val y: Int = selection!!.y
    val width: Int = selection!!.width
    val height: Int = selection!!.height

    if (e.x < x || e.x > x + width || e.y < y || e.y > y + height) {
      panel.cursor = Cursor.getDefaultCursor()
    } else if (e.x < x + EDGE_SIZE && e.y < y + EDGE_SIZE) {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
    } else if (e.x > x + width - EDGE_SIZE && e.y < y + EDGE_SIZE) {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
    } else if (e.x < x + EDGE_SIZE && e.y > y + height - EDGE_SIZE) {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
    } else if (e.x > x + width - EDGE_SIZE && e.y > y + height - EDGE_SIZE) {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
    } else if (e.x < x + EDGE_SIZE || e.x > x + width - EDGE_SIZE) {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
    } else if (e.y < y + EDGE_SIZE || e.y > y + height - EDGE_SIZE) {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
    }
  }

  override fun mouseReleased(e: MouseEvent) {
    selectionMade = selection != null
    isMovingSelection = false
    resizeMode = ResizeMode.RESIZE_NONE
    panel.updateButtonPosition()
  }

  override fun mouseClicked(e: MouseEvent) = Unit
  override fun mouseEntered(e: MouseEvent) = Unit
  override fun mouseExited(e: MouseEvent) = Unit

}

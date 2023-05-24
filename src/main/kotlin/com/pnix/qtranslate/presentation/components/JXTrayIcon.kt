package com.pnix.qtranslate.presentation.components

import java.awt.Frame
import java.awt.Image
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JDialog
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener


class JXTrayIcon(image: Image?, tooltip: String? = "") : TrayIcon(image, tooltip) {
  var jPopupMenu: JPopupMenu? = null
    set(value) {
      field = value
      field?.removePopupMenuListener(popupListener)
      field?.addPopupMenuListener(popupListener)
    }

  init {
    addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        showJPopupMenu(e)
      }

      override fun mouseReleased(e: MouseEvent) {
        showJPopupMenu(e)
      }
    })
  }

  fun showJPopupMenu(e: MouseEvent) {
    if (e.isPopupTrigger && jPopupMenu != null) {
      val size = jPopupMenu!!.preferredSize
      showJPopupMenu(e.x, e.y - size.height)
    }
  }

  private fun showJPopupMenu(x: Int, y: Int) {
    dialog!!.setLocation(x, y)
    dialog!!.isVisible = true
    jPopupMenu!!.show(dialog!!.contentPane, 0, 0)
    dialog!!.toFront()
  }

  companion object {
    private var dialog: JDialog? = null

    init {
      dialog = JDialog(null as Frame?, false)
      dialog!!.isUndecorated = true
      dialog!!.isAlwaysOnTop = true
    }


    private val popupListener: PopupMenuListener = object : PopupMenuListener {
      override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
      override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
        dialog!!.isVisible = false
      }

      override fun popupMenuCanceled(e: PopupMenuEvent) {
        dialog!!.isVisible = false
      }
    }
  }
}
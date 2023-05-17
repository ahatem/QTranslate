package com.pnix.qtranslate.presentation.loading

import java.awt.Dimension
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JProgressBar
import javax.swing.Timer
import javax.swing.border.EtchedBorder


class LoadingDialog : JDialog(null as Frame?, "", false) {

  val timer = Timer(10) { _ ->
    setLocation(MouseInfo.getPointerInfo().location.x, MouseInfo.getPointerInfo().location.y + 20)
  }

  init {
    isUndecorated = true
    isAlwaysOnTop = true
    focusableWindowState = false

    val progressBar = JProgressBar()
    progressBar.isIndeterminate = true
    progressBar.preferredSize = Dimension(50, 10)
    progressBar.border = BorderFactory.createEtchedBorder(EtchedBorder.RAISED)
    progressBar.putClientProperty("JProgressBar.square", true)

    add(progressBar)

    addComponentListener(object : ComponentAdapter() {
      override fun componentShown(e: ComponentEvent?) {
        timer.start()
      }

      override fun componentHidden(e: ComponentEvent?) {
        timer.stop()
      }
    })

    pack()
  }
}
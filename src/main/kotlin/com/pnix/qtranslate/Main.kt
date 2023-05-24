package com.pnix.qtranslate

import com.formdev.flatlaf.FlatLaf
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import kong.unirest.Unirest
import javax.swing.SwingUtilities
import javax.swing.UIManager

/*
* organize folders and try to make more swing component to use again like TextArea for example with zoom (TTextArea.kt)
* */

/*
* sending GET requests with different IPs
* */
fun test() {
  // from megabasterd proxy list
  val ipAddresses = arrayOf(
    "167.172.148.136:80",
    "178.33.198.181:3128",
    "5.172.177.196:3128",
    "178.128.148.144:80",
    "41.65.168.49:1981",
    "89.132.144.41:9090",
    "45.92.108.112:8080"
  )

  for (proxyAddress in ipAddresses) {
    val parts = proxyAddress.split(":")
    val ipAddress = parts[0]
    val port = parts[1].toInt()
    runCatching {
      val response = Unirest.get("http://example.com/")
        .proxy(ipAddress, port)
        .asString()
        .body
      println(response)
    }
  }
}

fun main() {
  FlatLaf.setup(Configurations.theme.lookAndFeel)
  UIManager.put("TitlePane.showIcon", false)
  UIManager.put("ScrollBar.showButtons", false)
  SwingUtilities.invokeLater { QTranslateFrame().isVisible = true }
}
package com.pnix.qtranslate

import com.formdev.flatlaf.FlatLaf
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import kong.unirest.Unirest
import javax.swing.SwingUtilities
import javax.swing.UIManager


/*
* http://web.archive.org/web/20230227130154/http://quest-app.appspot.com/home
* organize folders and try to make more swing component to use again like TextArea for example with zoom (TTextArea.kt)
*   - Make horizontal scroll bar appears if width is less than 200 for ex .. or find a way to force wrap it
*     NOTE: the problem is just appears in RTL Lang like arabic
* TODO
* =========
* 1 - Implement Search in History Dialog
* 2 - Services and languages in settings are not functional
* 3 - Auto-detect not implemented
*/

fun main() {
  SwingUtilities.invokeLater {
    FlatLaf.setup(Configurations.theme.lookAndFeel)
    FlatLaf.setUseNativeWindowDecorations(Configurations.enableWindowStyle)
    UIManager.put("TitlePane.unifiedBackground", Configurations.unifyTitleBar)
    UIManager.put("TitlePane.showIcon", false)
    UIManager.put("ScrollBar.showButtons", false)

    QTranslateFrame().isVisible = true
  }
}

fun `sending GET requests with different IPs`() {
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
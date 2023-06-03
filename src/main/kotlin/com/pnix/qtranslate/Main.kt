package com.pnix.qtranslate

import com.formdev.flatlaf.FlatLaf
import com.google.gson.Gson
import com.pnix.qtranslate.common.Localizer
import com.pnix.qtranslate.common.UserAgent
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import kong.unirest.Unirest
import java.awt.ComponentOrientation
import javax.swing.SwingUtilities
import javax.swing.UIManager

/*
* IN-PROGRESS:
* TODO:
*   Auto-detect not implemented
*   Create LanguageComboBox to handle all logic for that
*   Add Auto-Complete POPUP TO TTextArea
*   Audio can't be stopped ... so make stop it (if same text OR the content of the clipboard not changed)
*   Languages in settings are not functional
*/

fun main() {
  SwingUtilities.invokeLater {
    FlatLaf.setup(Configurations.theme.lookAndFeel)
    FlatLaf.setUseNativeWindowDecorations(Configurations.enableWindowStyle)
    UIManager.put("TitlePane.unifiedBackground", Configurations.unifyTitleBar)
    UIManager.put("TitlePane.showIcon", false)
    UIManager.put("ScrollBar.showButtons", false)

    QTranslateFrame().apply {
      applyComponentOrientation(ComponentOrientation.getOrientation(Localizer.currentLocale))
      isVisible = true
    }
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

fun getAutoComplete(text: String) {
  val response = Unirest.get("http://google.com/complete/search?client=chrome&q=$text")
    .header("user-agent", UserAgent.random())
    .asString()

  val gson = Gson()
  val data = gson.fromJson(response.body, Array<Any>::class.java)
  val autoCompletionsString = data[1].toString()
  val autoCompletions = autoCompletionsString
    .substring( 1, autoCompletionsString.length - 1 )
    .split(", ")
    .filter { !it.matches(Regex("^https?://.*")) }
    .take(10)

  autoCompletions.forEach {
    println(it)
  }

//  for result in json.loads(response.text)[1]:
}
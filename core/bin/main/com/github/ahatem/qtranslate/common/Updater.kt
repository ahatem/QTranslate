package com.github.ahatem.qtranslate.common

import org.jsoup.Jsoup

data class LatestVersionInfo(val versionCode: Int, val versionName: String, val releaseNotes: String) {
  fun isNewerVersion() = versionCode > QTranslate.VERSION_NUMBER
}

object Updater {

  fun getLatestVersionInfo(): LatestVersionInfo? {
    runCatching {
      val doc = Jsoup.connect("https://qtranslate-app.web.app/").get()
      val latestVersion = doc.select("details[data-version-code]").first()!!
      val versionCode = latestVersion.attr("data-version-code").toInt()
      val versionName = latestVersion.select("span.title").text().replace("QTranslate Version", "").trim()
      val releaseNotes = latestVersion.select("div.content ul").html().trim()

      return LatestVersionInfo(versionCode, versionName, releaseNotes)
    }

    return null
  }
}